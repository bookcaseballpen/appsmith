package com.appsmith.server.catalog;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.approvals.ApprovalStatus;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationApproval;
import com.appsmith.server.domains.ApplicationPage;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.dtos.PageDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.repositories.ApplicationApprovalRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.solutions.ApplicationPermission;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.appsmith.server.helpers.DateUtils.ISO_FORMATTER;

/**
 * Fork: the home page catalog and app tags.
 *
 * <p>Every read goes through the application repository with the caller's read permission, so the app grants decide
 * what each user sees, and a USER sees only live apps ({@code CustomApplicationRepositoryImpl}); the filters, sort and
 * paging are part of the same query.
 *
 * <p>Tags are app metadata: a DEV with an EDIT grant on the app or an ADMIN sets them here, also while the editor is
 * read-only, and through MCP ({@code set_app_tags}). They are not part of the approval fingerprint, so changing them
 * never cancels a pending release request or takes a live app offline.
 */
@Service
public class ApplicationCatalogService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 100;

    private static final String SET_TAGS_ACTION = "Set app tags";

    /** The catalog's sort fields; anything else is refused. */
    public enum SortField {
        NAME(Application.Fields.name, Sort.Direction.ASC),
        LAST_DEPLOYED_AT(Application.Fields.lastDeployedAt, Sort.Direction.DESC),
        UPDATED_AT(BaseDomain.Fields.updatedAt, Sort.Direction.DESC),
        CREATED_AT(BaseDomain.Fields.createdAt, Sort.Direction.DESC);

        private final String field;
        private final Sort.Direction defaultDirection;

        SortField(String field, Sort.Direction defaultDirection) {
            this.field = field;
            this.defaultDirection = defaultDirection;
        }

        static SortField parse(String value) {
            if (!StringUtils.hasText(value)) {
                return NAME;
            }
            return switch (value.strip()) {
                case "name" -> NAME;
                case "lastDeployedAt" -> LAST_DEPLOYED_AT;
                case "updatedAt" -> UPDATED_AT;
                case "createdAt" -> CREATED_AT;
                default -> throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "sort");
            };
        }
    }

    /** The catalog's query parameters, as the endpoint receives them (any of them may be null). */
    public record CatalogQuery(
            String search, List<String> tags, String tagMode, String sort, String order, Integer page, Integer size) {}

    private final ApplicationRepository applicationRepository;
    private final NewPageRepository newPageRepository;
    private final ApplicationApprovalRepository applicationApprovalRepository;
    private final ApplicationPermission applicationPermission;
    private final InstanceRoleService instanceRoleService;

    // Lazy, as in AppGrantService: the role service reaches the application services through UserUtils.
    public ApplicationCatalogService(
            ApplicationRepository applicationRepository,
            NewPageRepository newPageRepository,
            ApplicationApprovalRepository applicationApprovalRepository,
            ApplicationPermission applicationPermission,
            @Lazy InstanceRoleService instanceRoleService) {
        this.applicationRepository = applicationRepository;
        this.newPageRepository = newPageRepository;
        this.applicationApprovalRepository = applicationApprovalRepository;
        this.applicationPermission = applicationPermission;
        this.instanceRoleService = instanceRoleService;
    }

    /** One page of the apps the caller can read that match the query. */
    public Mono<ApplicationCatalogDTO> getCatalog(CatalogQuery query) {
        return Mono.defer(() -> {
            int size = query.size() == null ? DEFAULT_PAGE_SIZE : query.size();
            int page = query.page() == null ? 0 : query.page();
            if (size < 1 || size > MAX_PAGE_SIZE) {
                return Mono.error(new AppsmithException(
                        AppsmithError.INVALID_PARAMETER, "size (from 1 to " + MAX_PAGE_SIZE + ")"));
            }
            if (page < 0 || (long) page * size > Integer.MAX_VALUE) {
                return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "page"));
            }
            SortField sortField = SortField.parse(query.sort());
            Sort.Direction direction = direction(query.order(), sortField.defaultDirection);
            // The id breaks ties, so paging never shows an app twice or skips one.
            Sort sort = Sort.by(direction, sortField.field).and(Sort.by(Sort.Direction.ASC, BaseDomain.Fields.id));
            BridgeQuery<Application> criteria = criteria(query);
            AclPermission read = applicationPermission.getReadPermission();

            Mono<List<Application>> applications = applicationRepository
                    .queryBuilder()
                    .criteria(criteria)
                    .permission(read)
                    .sort(sort)
                    .skip(page * size)
                    .limit(size)
                    .all()
                    .collectList();
            Mono<Long> total = applicationRepository
                    .queryBuilder()
                    .criteria(criteria)
                    .permission(read)
                    .count();

            return Mono.zip(applications, total, instanceRoleService.getCurrentUserRole())
                    .flatMap(tuple -> items(tuple.getT1(), tuple.getT3())
                            .map(items -> new ApplicationCatalogDTO(items, tuple.getT2(), page, size)));
        });
    }

    /** Every tag on an app the caller can read, sorted. */
    public Mono<List<String>> getTags() {
        return applicationRepository
                .queryBuilder()
                .criteria(Bridge.isNotNull(Application.Fields.tags))
                .permission(applicationPermission.getReadPermission())
                .all(TagsOnly.class)
                .flatMapIterable(app -> app.tags() == null ? List.of() : app.tags())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new))
                .map(List::copyOf);
    }

    /**
     * A DEV with an EDIT grant on the app, or an ADMIN, replaces its tags. More than {@link ApplicationTags#MAX_TAGS}
     * tags, or a tag over {@link ApplicationTags#MAX_LENGTH} characters, is refused.
     */
    public Mono<ApplicationCatalogDTO.TagsResult> setTags(String applicationId, List<String> tags) {
        return Mono.fromCallable(() -> ApplicationTags.validate(tags)).flatMap(normalised -> instanceRoleService
                .requireCurrentUserRole(SET_TAGS_ACTION, InstanceRole.DEV, InstanceRole.ADMIN)
                .then(writeTags(applicationId, normalised)));
    }

    /**
     * The tags of an app JSON pushed over the app ({@code ProjectsRepoService}), clamped like an import's. The caller
     * already holds the import's permissions; this needs the edit permission on the app, as the import does.
     */
    public Mono<ApplicationCatalogDTO.TagsResult> setImportedTags(String applicationId, List<String> tags) {
        List<String> clamped = ApplicationTags.clamp(tags == null ? List.of() : tags);
        return instanceRoleService
                .requireCurrentUserRole(SET_TAGS_ACTION, InstanceRole.DEV, InstanceRole.ADMIN)
                .then(writeTags(applicationId, clamped));
    }

    // Only the tags (and updatedAt) are written, so a tag change never touches what the approval fingerprint reads.
    private Mono<ApplicationCatalogDTO.TagsResult> writeTags(String applicationId, List<String> tags) {
        if (!StringUtils.hasText(applicationId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.APPLICATION_ID));
        }
        return applicationRepository
                .queryBuilder()
                .byId(applicationId)
                .permission(applicationPermission.getEditPermission())
                .updateFirst(Bridge.update()
                        .set(Application.Fields.tags, tags)
                        .set(BaseDomain.Fields.updatedAt, Instant.now()))
                .flatMap(matched -> matched == 0
                        ? Mono.error(new AppsmithException(
                                AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.APPLICATION, applicationId))
                        : Mono.just(new ApplicationCatalogDTO.TagsResult(applicationId, tags)));
    }

    private static Sort.Direction direction(String order, Sort.Direction defaultDirection) {
        if (!StringUtils.hasText(order)) {
            return defaultDirection;
        }
        return switch (order.strip().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "order");
        };
    }

    private static BridgeQuery<Application> criteria(CatalogQuery query) {
        BridgeQuery<Application> criteria = Bridge.query();

        String search = query.search() == null ? "" : query.search().strip();
        if (search.length() > MAX_SEARCH_LENGTH) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "search");
        }
        if (!search.isEmpty()) {
            // searchIgnoreCase quotes the text, so it is matched literally. Tags are stored lower-cased.
            criteria.and(Bridge.<Application>or(
                    Bridge.<Application>searchIgnoreCase(Application.Fields.name, search),
                    Bridge.<Application>searchIgnoreCase(Application.Fields.description, search),
                    Bridge.<Application>searchIgnoreCase(Application.Fields.tags, search)));
        }

        List<String> tags = ApplicationTags.normalise(
                query.tags() == null
                        ? List.of()
                        : query.tags().stream()
                                // tags=a,b and tags=a&tags=b mean the same.
                                .filter(Objects::nonNull)
                                .flatMap(tag -> List.of(tag.split(",")).stream())
                                .toList());
        if (tags.size() > ApplicationTags.MAX_TAGS) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "tags");
        }
        boolean all = isAll(query.tagMode());
        if (!tags.isEmpty()) {
            if (all) {
                // On a list field, equal matches a list holding the value.
                tags.forEach(tag -> criteria.equal(Application.Fields.tags, tag));
            } else {
                criteria.in(Application.Fields.tags, tags);
            }
        }
        return criteria;
    }

    private static boolean isAll(String tagMode) {
        if (!StringUtils.hasText(tagMode)) {
            return false;
        }
        return switch (tagMode.strip().toLowerCase(Locale.ROOT)) {
            case "any" -> false;
            case "all" -> true;
            default -> throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "tagMode");
        };
    }

    private Mono<List<ApplicationCatalogDTO.Item>> items(List<Application> applications, InstanceRole role) {
        if (applications.isEmpty()) {
            return Mono.just(List.of());
        }
        Map<String, ApplicationPage> defaultPages = applications.stream()
                .map(application -> Map.entry(application.getId(), Optional.ofNullable(defaultPage(application))))
                .filter(entry -> entry.getValue().isPresent())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().get()));
        Set<String> pageIds =
                defaultPages.values().stream().map(ApplicationPage::getId).collect(Collectors.toSet());
        Set<String> applicationIds =
                applications.stream().map(Application::getId).collect(Collectors.toSet());

        // The apps are already readable, so their pages and approval states are read without another check.
        Mono<Map<String, NewPage>> pages = pageIds.isEmpty()
                ? Mono.just(Map.of())
                : newPageRepository
                        .queryBuilder()
                        .criteria(Bridge.in(NewPage.Fields.id, pageIds))
                        .all()
                        .collectMap(NewPage::getId, Function.identity());
        Mono<Map<String, ApprovalStatus>> approvals = role == InstanceRole.USER
                ? Mono.just(Map.of())
                : applicationApprovalRepository
                        .findAllByApplicationIdIn(applicationIds)
                        .filter(approval -> approval.getStatus() != null)
                        .collectMap(ApplicationApproval::getApplicationId, ApplicationApproval::getStatus);
        boolean mayEdit = role == InstanceRole.DEV || role == InstanceRole.ADMIN;
        String edit = applicationPermission.getEditPermission().getValue();

        return Mono.zip(pages, approvals).map(tuple -> applications.stream()
                .map(application -> {
                    ApplicationPage defaultPage = defaultPages.get(application.getId());
                    NewPage page = defaultPage == null ? null : tuple.getT1().get(defaultPage.getId());
                    return new ApplicationCatalogDTO.Item(
                            application.getId(),
                            application.getBaseId(),
                            application.getWorkspaceId(),
                            application.getName(),
                            application.getDescription(),
                            application.getSlug(),
                            uniqueSlug(application),
                            application.getIcon(),
                            application.getColor(),
                            application.getTags() == null ? List.of() : application.getTags(),
                            application.getLastDeployedAt(),
                            iso(application.getUpdatedAt()),
                            defaultPage == null ? null : defaultPage.getBaseId(),
                            pageSlug(page, isLive(application)),
                            tuple.getT2().get(application.getId()),
                            mayEdit
                                    && application.getUserPermissions() != null
                                    && application.getUserPermissions().contains(edit));
                })
                .toList());
    }

    private static boolean isLive(Application application) {
        return application.getLastDeployedAt() != null;
    }

    // The page a visitor lands on: the live default page of a live app, the edit copy's otherwise.
    private static ApplicationPage defaultPage(Application application) {
        Collection<ApplicationPage> pages =
                isLive(application) ? application.getPublishedPages() : application.getPages();
        if (pages == null) {
            return null;
        }
        return pages.stream()
                .filter(ApplicationPage::isDefault)
                .findFirst()
                .orElse(pages.stream().findFirst().orElse(null));
    }

    private static String pageSlug(NewPage page, boolean live) {
        if (page == null) {
            return null;
        }
        PageDTO side = live && page.getPublishedPage() != null ? page.getPublishedPage() : page.getUnpublishedPage();
        return side == null ? null : side.getSlug();
    }

    private static String uniqueSlug(Application application) {
        Application.StaticUrlSettings settings = application.getStaticUrlSettings();
        return settings != null && Boolean.TRUE.equals(settings.getEnabled()) ? settings.getUniqueSlug() : null;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : ISO_FORMATTER.format(instant);
    }

    /** The tags of an app, for the distinct-tags read. */
    public record TagsOnly(List<String> tags) {}
}

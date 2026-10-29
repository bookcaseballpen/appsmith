package com.appsmith.server.approvals;

import com.appsmith.server.domains.ApplicationPage;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.repositories.ActionCollectionRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.ThemeRepository;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A SHA-256 over everything a publish copies from edit mode to view mode: the page list, each page (layouts and DSL),
 * every query and JS object, the app layout and settings, the custom JS libraries and the theme.
 *
 * <p>The same function reads either side ({@code viewMode}), so the fingerprint of the edit-mode version an approver
 * approved can be compared with the view-mode copy a publish produced. Any change to the content, through the editor,
 * an import, a git pull or MCP, changes it, whichever code path wrote it, because it is computed from what is stored,
 * not from events. Fields that change without an edit (timestamps, permissions, ids of the user who saved) are left
 * out, and entries are sorted, so reading the same content twice always gives the same value.
 */
@Component
public class ApprovalFingerprint {

    // Not content: set by saves, reads or the permission system.
    private static final Set<String> IGNORED_FIELDS = Set.of(
            "userPermissions",
            "policies",
            "policyMap",
            "createdAt",
            "updatedAt",
            "createdBy",
            "modifiedBy",
            "lastUpdatedTime");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            // Stored fields only: computed getters are not content and some depend on the caller.
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ApplicationRepository applicationRepository;
    private final NewPageRepository newPageRepository;
    private final NewActionRepository newActionRepository;
    private final ActionCollectionRepository actionCollectionRepository;
    private final ThemeRepository themeRepository;

    public ApprovalFingerprint(
            ApplicationRepository applicationRepository,
            NewPageRepository newPageRepository,
            NewActionRepository newActionRepository,
            ActionCollectionRepository actionCollectionRepository,
            ThemeRepository themeRepository) {
        this.applicationRepository = applicationRepository;
        this.newPageRepository = newPageRepository;
        this.newActionRepository = newActionRepository;
        this.actionCollectionRepository = actionCollectionRepository;
        this.themeRepository = themeRepository;
    }

    /** The edit-mode version: what a publish would copy now. */
    public Mono<String> ofEditMode(String applicationId) {
        return of(applicationId, false);
    }

    /** The view-mode version: what users see. */
    public Mono<String> ofViewMode(String applicationId) {
        return of(applicationId, true);
    }

    private Mono<String> of(String applicationId, boolean viewMode) {
        return applicationRepository.findById(applicationId).flatMap(application -> {
            List<ApplicationPage> pages = Objects.requireNonNullElse(
                    viewMode ? application.getPublishedPages() : application.getPages(), List.of());
            Set<String> pageIds = pages.stream().map(ApplicationPage::getId).collect(Collectors.toSet());

            Mono<ArrayNode> pageContents = newPageRepository
                    .findByApplicationId(applicationId)
                    .filter(page -> pageIds.contains(page.getId()))
                    .map(page -> viewMode ? page.getPublishedPage() : page.getUnpublishedPage())
                    .filter(Objects::nonNull)
                    .filter(page -> page.getDeletedAt() == null)
                    .collectList()
                    .map(ApprovalFingerprint::sortedArray);

            Mono<ArrayNode> actions = newActionRepository
                    .findByApplicationId(applicationId)
                    .map(action -> viewMode ? action.getPublishedAction() : action.getUnpublishedAction())
                    .filter(Objects::nonNull)
                    .filter(action -> action.getDeletedAt() == null)
                    .collectList()
                    .map(ApprovalFingerprint::sortedArray);

            Mono<ArrayNode> collections = actionCollectionRepository
                    .findByApplicationId(applicationId)
                    .map(collection ->
                            viewMode ? collection.getPublishedCollection() : collection.getUnpublishedCollection())
                    .filter(Objects::nonNull)
                    .filter(collection -> collection.getDeletedAt() == null)
                    .collectList()
                    .map(ApprovalFingerprint::sortedArray);

            String themeId = viewMode ? application.getPublishedModeThemeId() : application.getEditModeThemeId();
            Mono<JsonNode> theme = StringUtils.hasLength(themeId)
                    ? themeRepository.findById(themeId).map(ApprovalFingerprint::themeContent)
                    : Mono.empty();

            return Mono.zip(
                            pageContents,
                            actions,
                            collections,
                            theme.defaultIfEmpty(JsonNodeFactory.instance.nullNode()))
                    .map(parts -> {
                        ObjectNode root = JsonNodeFactory.instance.objectNode();
                        root.set("pages", canonical(MAPPER.valueToTree(pages)));
                        root.set("pageContents", parts.getT1());
                        root.set("actions", parts.getT2());
                        root.set("actionCollections", parts.getT3());
                        root.set("theme", parts.getT4());
                        root.set(
                                "appLayout",
                                canonical(MAPPER.valueToTree(
                                        viewMode
                                                ? application.getPublishedAppLayout()
                                                : application.getUnpublishedAppLayout())));
                        root.set(
                                "applicationDetail",
                                canonical(MAPPER.valueToTree(
                                        viewMode
                                                ? application.getPublishedApplicationDetail()
                                                : application.getUnpublishedApplicationDetail())));
                        root.set(
                                "customJSLibs",
                                sortedArray(Objects.requireNonNullElse(
                                        viewMode
                                                ? application.getPublishedCustomJSLibs()
                                                : application.getUnpublishedCustomJSLibs(),
                                        Set.of())));
                        return sha256(root.toString());
                    });
        });
    }

    // Publishing copies a customised theme into another document, so its id differs between modes: compare content.
    private static JsonNode themeContent(Theme theme) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("config", canonical(MAPPER.valueToTree(theme.getConfig())));
        node.set("properties", canonical(MAPPER.valueToTree(theme.getProperties())));
        node.set("stylesheet", canonical(MAPPER.valueToTree(theme.getStylesheet())));
        return node;
    }

    // Repository order is not guaranteed, so entries are sorted by their canonical text.
    private static ArrayNode sortedArray(Collection<?> items) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        items.stream()
                .map(item -> canonical(MAPPER.valueToTree(item)).toString())
                .sorted()
                .forEach(array::add);
        return array;
    }

    /** Object keys sorted, ignored fields dropped, at every depth. Array order is content and stays. */
    static JsonNode canonical(JsonNode node) {
        if (node == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (!IGNORED_FIELDS.contains(name)) {
                    names.add(name);
                }
            }
            names.sort(null);
            names.forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return node;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

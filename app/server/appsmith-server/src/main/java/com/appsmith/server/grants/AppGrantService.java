package com.appsmith.server.grants;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.repositories.ActionCollectionRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.AppsmithRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.ThemeRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.solutions.PolicySolution;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Per-app access: an administrator grants a team or a single user VIEW or EDIT on one app (see {@link GrantLevel}).
 *
 * <p>A grant is written into the policies of the app and of its pages, queries, JS objects and custom themes, exactly
 * as CE's "make public" does for the anonymous group ({@code ApplicationServiceCEImpl.changeViewAccess...}), but for
 * any group and level. A team grant goes to the team's group; a user grant goes to the user's personal group (see
 * {@link GrantGroups}), created on their first grant. Pages, queries and JS objects created later inherit the app's
 * policies, so they inherit the grant; an import over the app keeps the app's policies, so it keeps its grants.
 * Datasources are not part of a grant: DEV and USER execute every datasource through their role.
 *
 * <p>A DEV who creates an app (create, duplicate, import as a new app, also through MCP) gets an EDIT grant on it
 * before the app is saved, see {@link #withCreatorGrant}.
 */
@Service
public class AppGrantService {

    private static final String MANAGE_GRANTS_ACTION = "Manage app access";

    private final ApplicationRepository applicationRepository;
    private final NewPageRepository newPageRepository;
    private final NewActionRepository newActionRepository;
    private final ActionCollectionRepository actionCollectionRepository;
    private final ThemeRepository themeRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final UserRepository userRepository;
    private final CacheableRepositoryHelper cacheableRepositoryHelper;
    private final PolicySolution policySolution;
    private final InstanceRoleService instanceRoleService;

    // Lazy: the role service reaches the application services through UserUtils, and they depend on this service.
    public AppGrantService(
            ApplicationRepository applicationRepository,
            NewPageRepository newPageRepository,
            NewActionRepository newActionRepository,
            ActionCollectionRepository actionCollectionRepository,
            ThemeRepository themeRepository,
            PermissionGroupRepository permissionGroupRepository,
            UserRepository userRepository,
            CacheableRepositoryHelper cacheableRepositoryHelper,
            PolicySolution policySolution,
            @Lazy InstanceRoleService instanceRoleService) {
        this.applicationRepository = applicationRepository;
        this.newPageRepository = newPageRepository;
        this.newActionRepository = newActionRepository;
        this.actionCollectionRepository = actionCollectionRepository;
        this.themeRepository = themeRepository;
        this.permissionGroupRepository = permissionGroupRepository;
        this.userRepository = userRepository;
        this.cacheableRepositoryHelper = cacheableRepositoryHelper;
        this.policySolution = policySolution;
        this.instanceRoleService = instanceRoleService;
    }

    /**
     * The teams and users granted the app, for an administrator.
     */
    public Mono<List<AppGrantDTO>> getGrants(String applicationId) {
        return requireAdmin().then(findApplication(applicationId)).flatMap(this::grantsOf);
    }

    /**
     * An administrator sets one grantee's level on the app (NONE revokes) and gets the app's grants back.
     */
    public Mono<List<AppGrantDTO>> setGrant(String applicationId, AppGrantDTO.Request request) {
        if (request == null
                || request.grantee() == null
                || request.grantee().type() == null
                || !StringUtils.hasText(request.grantee().id())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "grantee"));
        }
        if (request.level() == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "level"));
        }
        boolean granting = request.level() != GrantLevel.NONE;
        return requireAdmin().then(findApplication(applicationId)).flatMap(application -> granteeGroupId(
                        request.grantee(), granting)
                .flatMap(groupId -> setLevel(application, groupId, request.level()))
                .then(Mono.defer(() -> findApplication(applicationId)))
                .flatMap(this::grantsOf));
    }

    /**
     * Gives a DEV creating {@code application} an EDIT grant on it, in the policies of the unsaved app, so the pages,
     * queries and JS objects created with it inherit the grant. Other roles are returned unchanged: an ADMIN edits every
     * app, and nobody else may create one.
     */
    public Mono<Application> withCreatorGrant(Application application, User creator) {
        return instanceRoleService
                .getRole(creator)
                .flatMap(role -> role != InstanceRole.DEV
                        ? Mono.just(application)
                        : personalGroupId(creator, true)
                                .map(groupId -> policySolution.addPoliciesToExistingObject(
                                        GrantPolicies.of(policySolution, GrantLevel.EDIT, groupId)
                                                .application(),
                                        application)));
    }

    /**
     * Removes every grant the group holds, before the group (a team) is deleted.
     */
    public Mono<Void> revokeEverywhere(String groupId) {
        return applicationRepository
                .queryBuilder()
                .permission(AclPermission.READ_APPLICATIONS)
                .permissionGroups(Set.of(groupId))
                .all()
                .concatMap(application -> setLevel(application, groupId, GrantLevel.NONE))
                .then();
    }

    /**
     * The team with this id, or NO_RESOURCE_FOUND.
     */
    Mono<PermissionGroup> findTeam(String teamId) {
        return permissionGroupRepository
                .queryBuilder()
                .criteria(Bridge.<PermissionGroup>equal(PermissionGroup.Fields.id, teamId)
                        .equal(PermissionGroup.Fields.defaultDomainType, GrantGroups.TEAM))
                .one()
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, "team", teamId)));
    }

    /**
     * Clears a user's cached permission groups after their groups changed.
     */
    Mono<Void> evictPermissionGroupCache(User user) {
        Mono<String> organizationId = user.getOrganizationId() != null
                ? Mono.just(user.getOrganizationId())
                : cacheableRepositoryHelper.getCurrentUserOrganizationId();
        return organizationId.flatMap(
                id -> permissionGroupRepository.evictAllPermissionGroupCachesForUser(user.getEmail(), id));
    }

    Mono<Void> requireAdmin() {
        return instanceRoleService.requireCurrentUserRole(MANAGE_GRANTS_ACTION, InstanceRole.ADMIN);
    }

    private Mono<Application> findApplication(String applicationId) {
        return applicationRepository
                .findById(applicationId)
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION, applicationId)));
    }

    /**
     * The group a grant to the grantee goes to. Empty for a user without a personal group when {@code create} is false
     * (a revoke): they hold no grant.
     */
    private Mono<String> granteeGroupId(AppGrantDTO.Grantee grantee, boolean create) {
        if (grantee.type() == AppGrantDTO.Type.TEAM) {
            return findTeam(grantee.id()).map(PermissionGroup::getId);
        }
        return userRepository
                .findById(grantee.id())
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.USER, grantee.id())))
                .flatMap(user -> personalGroupId(user, create));
    }

    private Mono<String> personalGroupId(User user, boolean create) {
        Mono<String> existing = permissionGroupRepository
                .queryBuilder()
                .criteria(Bridge.<PermissionGroup>equal(PermissionGroup.Fields.defaultDomainId, user.getId())
                        .equal(PermissionGroup.Fields.defaultDomainType, GrantGroups.USER_GRANT))
                .first()
                .map(PermissionGroup::getId);
        return create ? existing.switchIfEmpty(Mono.defer(() -> createPersonalGroup(user))) : existing;
    }

    private Mono<String> createPersonalGroup(User user) {
        Mono<String> organizationId = user.getOrganizationId() != null
                ? Mono.just(user.getOrganizationId())
                : cacheableRepositoryHelper.getCurrentUserOrganizationId();
        return organizationId.flatMap(id -> {
            PermissionGroup group = new PermissionGroup();
            group.setName("App grants of " + user.getEmail());
            group.setDescription("Holds the app grants given to this user alone");
            group.setDefaultDomainId(user.getId());
            group.setDefaultDomainType(GrantGroups.USER_GRANT);
            group.setOrganizationId(id);
            group.setPermissions(Set.of());
            group.setAssignedToUserIds(new HashSet<>(Set.of(user.getId())));
            return permissionGroupRepository.save(group).flatMap(saved -> evictPermissionGroupCache(user)
                    .thenReturn(saved.getId()));
        });
    }

    /**
     * Writes the group's level into the app and its pages, queries, JS objects and custom themes: whatever the group
     * held before is removed first, so changing EDIT to VIEW takes the edit rights away.
     */
    private Mono<Void> setLevel(Application application, String groupId, GrantLevel level) {
        GrantPolicies previous = GrantPolicies.of(policySolution, GrantLevel.EDIT, groupId);
        GrantPolicies next = GrantPolicies.of(policySolution, level, groupId);
        String applicationId = application.getId();

        Flux<NewPage> pages = newPageRepository
                .queryBuilder()
                .criteria(Bridge.equal(NewPage.Fields.applicationId, applicationId))
                .all();
        Flux<NewAction> actions = newActionRepository
                .queryBuilder()
                .criteria(Bridge.equal(NewAction.Fields.applicationId, applicationId))
                .all();
        Flux<ActionCollection> collections = actionCollectionRepository
                .queryBuilder()
                .criteria(Bridge.equal(ActionCollection.Fields.applicationId, applicationId))
                .all();
        List<String> themeIds = Stream.of(application.getEditModeThemeId(), application.getPublishedModeThemeId())
                .filter(StringUtils::hasText)
                .toList();
        Flux<Theme> themes = themeRepository
                .queryBuilder()
                .criteria(Bridge.<Theme>or(
                        Bridge.equal(Theme.Fields.applicationId, applicationId), Bridge.in(Theme.Fields.id, themeIds)))
                .all()
                .filter(theme -> !Boolean.TRUE.equals(theme.isSystemTheme()));

        return replacePolicies(newPageRepository, pages, previous.page(), next.page())
                .then(replacePolicies(newActionRepository, actions, previous.action(), next.action()))
                .then(replacePolicies(actionCollectionRepository, collections, previous.action(), next.action()))
                .then(replacePolicies(themeRepository, themes, previous.theme(), next.theme()))
                .then(replacePolicies(
                        applicationRepository, Flux.just(application), previous.application(), next.application()));
    }

    /**
     * Updates only the policies of each document, so a grant never overwrites a concurrent change to the document.
     */
    private <T extends BaseDomain> Mono<Void> replacePolicies(
            AppsmithRepository<T> repository,
            Flux<T> documents,
            Map<String, Policy> previous,
            Map<String, Policy> next) {
        return documents
                .concatMap(document -> {
                    // The permission checks read policyMap, so start from it rather than from getPolicies().
                    document.setPolicies(new HashSet<>(
                            document.getPolicyMap() == null
                                    ? Set.of()
                                    : document.getPolicyMap().values()));
                    policySolution.removePoliciesFromExistingObject(previous, document);
                    policySolution.addPoliciesToExistingObject(next, document);
                    return repository.updateByIdWithoutPermissionCheck(
                            document.getId(), policiesUpdate(document.getPolicyMap()));
                })
                .then();
    }

    /**
     * Sets both stored forms of a document's policies: the permission checks query {@code policyMap}, but
     * {@code getPolicies()} returns the deprecated {@code policies} set when it is not empty, and CE code that adds
     * policies to a child (a new page, an imported page) starts from that set. Both must stay the same.
     * <p>
     * Fork: on PostgreSQL {@code policyMap} is the only stored form ({@code policies} is not a column, and
     * {@code getPolicies()} derives it from {@code policyMap}).
     */
    public static BridgeUpdate policiesUpdate(Map<String, Policy> policyMap) {
        // Only policyMap: policies is @Transient on PostgreSQL, so setting it fails as an unknown field.
        return Bridge.update().set(BaseDomain.Fields.policyMap, policyMap);
    }

    private Mono<List<AppGrantDTO>> grantsOf(Application application) {
        Set<String> editors = groupsWith(application, AclPermission.MANAGE_APPLICATIONS);
        Set<String> granted = new HashSet<>(groupsWith(application, AclPermission.READ_APPLICATIONS));
        granted.addAll(editors);
        if (granted.isEmpty()) {
            return Mono.just(List.of());
        }
        return permissionGroupRepository
                .queryBuilder()
                .criteria(Bridge.<PermissionGroup>in(PermissionGroup.Fields.id, granted)
                        .in(PermissionGroup.Fields.defaultDomainType, GrantGroups.TYPES))
                .all()
                .concatMap(group -> toGrant(group, editors.contains(group.getId()) ? GrantLevel.EDIT : GrantLevel.VIEW))
                .sort(Comparator.comparing(
                                (AppGrantDTO grant) -> grant.grantee().type())
                        .thenComparing(grant -> grant.name() == null ? "" : grant.name()))
                .collectList();
    }

    private Mono<AppGrantDTO> toGrant(PermissionGroup group, GrantLevel level) {
        if (GrantGroups.TEAM.equals(group.getDefaultDomainType())) {
            return Mono.just(new AppGrantDTO(
                    new AppGrantDTO.Grantee(AppGrantDTO.Type.TEAM, group.getId()), group.getName(), level));
        }
        return userRepository
                .findById(group.getDefaultDomainId())
                .map(user -> new AppGrantDTO(
                        new AppGrantDTO.Grantee(AppGrantDTO.Type.USER, user.getId()), user.getEmail(), level));
    }

    private static Set<String> groupsWith(Application application, AclPermission permission) {
        Map<String, Policy> policyMap = application.getPolicyMap();
        Policy policy = policyMap == null ? null : policyMap.get(permission.getValue());
        return policy == null || policy.getPermissionGroups() == null
                ? Set.of()
                : policy.getPermissionGroups().stream().collect(Collectors.toSet());
    }

    /**
     * The policies one group gets from a grant at one level: on the app, and as CE derives them for its pages, its
     * queries and JS objects, and its themes. Empty for NONE.
     */
    private record GrantPolicies(
            Map<String, Policy> application,
            Map<String, Policy> page,
            Map<String, Policy> action,
            Map<String, Policy> theme) {

        static GrantPolicies of(PolicySolution policySolution, GrantLevel level, String groupId) {
            if (level == GrantLevel.NONE) {
                return new GrantPolicies(Map.of(), Map.of(), Map.of(), Map.of());
            }
            AclPermission permission =
                    level == GrantLevel.EDIT ? AclPermission.MANAGE_APPLICATIONS : AclPermission.READ_APPLICATIONS;
            Map<String, Policy> application =
                    policySolution.generatePolicyFromPermissionWithPermissionGroup(permission, groupId);
            Map<String, Policy> page = policySolution.generateInheritedPoliciesFromSourcePolicies(
                    application, Application.class, NewPage.class);
            Map<String, Policy> action =
                    policySolution.generateInheritedPoliciesFromSourcePolicies(page, NewPage.class, NewAction.class);
            Map<String, Policy> theme = policySolution.generateInheritedPoliciesFromSourcePolicies(
                    application, Application.class, Theme.class);
            return new GrantPolicies(application, page, action, theme);
        }
    }
}

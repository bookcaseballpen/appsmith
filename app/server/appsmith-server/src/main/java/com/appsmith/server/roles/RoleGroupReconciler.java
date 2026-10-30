package com.appsmith.server.roles;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.Permission;
import com.appsmith.server.grants.AppGrantService;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.ActionCollectionRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.AppsmithRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.ThemeRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.solutions.PolicySolution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps the four role groups of a running instance in step with {@link InstanceRole} after the role definitions
 * change (for example when DEV and USER stopped seeing every app). Runs at every startup, after the default workspace
 * is resolved, and is idempotent:
 * <ol>
 *   <li>If every role group already stores the permissions its role defines, nothing happens.</li>
 *   <li>Otherwise, on every app of the default workspace and on its pages, queries, JS objects and custom themes, each
 *       role group loses the permissions its role no longer gives on that document. Grants (teams and personal
 *       groups) and every other group are left as they are; nothing is added.</li>
 *   <li>The workspace's policies are rebuilt for the role groups, and last the groups store their new permissions, so
 *       a start that stops halfway runs the whole reconciliation again on the next start.</li>
 * </ol>
 * Datasources are not touched: the roles' datasource rights did not change.
 */
@Slf4j
@Component
public class RoleGroupReconciler {

    private final WorkspaceRepository workspaceRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final ApplicationRepository applicationRepository;
    private final NewPageRepository newPageRepository;
    private final NewActionRepository newActionRepository;
    private final ActionCollectionRepository actionCollectionRepository;
    private final ThemeRepository themeRepository;
    private final PolicySolution policySolution;
    private final PolicyGenerator policyGenerator;

    public RoleGroupReconciler(
            WorkspaceRepository workspaceRepository,
            PermissionGroupRepository permissionGroupRepository,
            ApplicationRepository applicationRepository,
            NewPageRepository newPageRepository,
            NewActionRepository newActionRepository,
            ActionCollectionRepository actionCollectionRepository,
            ThemeRepository themeRepository,
            PolicySolution policySolution,
            PolicyGenerator policyGenerator) {
        this.workspaceRepository = workspaceRepository;
        this.permissionGroupRepository = permissionGroupRepository;
        this.applicationRepository = applicationRepository;
        this.newPageRepository = newPageRepository;
        this.newActionRepository = newActionRepository;
        this.actionCollectionRepository = actionCollectionRepository;
        this.themeRepository = themeRepository;
        this.policySolution = policySolution;
        this.policyGenerator = policyGenerator;
    }

    /**
     * The permissions a role group stores: its role's workspace permissions, and as in a CE workspace, reading the
     * members of every role group and, for ADMIN, assigning and unassigning them.
     */
    static Set<Permission> groupPermissions(InstanceRole role, String workspaceId, Collection<String> roleGroupIds) {
        Set<Permission> permissions = new HashSet<>();
        role.getWorkspacePermissions().stream()
                .filter(permission -> permission.getEntity().equals(Workspace.class))
                .forEach(permission -> permissions.add(new Permission(workspaceId, permission)));
        roleGroupIds.forEach(groupId -> {
            permissions.add(new Permission(groupId, AclPermission.READ_PERMISSION_GROUP_MEMBERS));
            if (role == InstanceRole.ADMIN) {
                permissions.add(new Permission(groupId, AclPermission.ASSIGN_PERMISSION_GROUPS));
                permissions.add(new Permission(groupId, AclPermission.UNASSIGN_PERMISSION_GROUPS));
            }
        });
        return permissions;
    }

    public Mono<Void> reconcile(DefaultWorkspaceIds ids) {
        Collection<String> roleGroupIds = ids.groupIds().values();
        return permissionGroupRepository
                .findAllById(Set.copyOf(roleGroupIds))
                .collectMap(PermissionGroup::getId)
                .flatMap(stored -> {
                    Map<InstanceRole, Set<Permission>> desired = new EnumMap<>(InstanceRole.class);
                    for (InstanceRole role : InstanceRole.values()) {
                        desired.put(role, groupPermissions(role, ids.workspaceId(), roleGroupIds));
                    }
                    boolean inStep = desired.entrySet().stream().allMatch(entry -> {
                        PermissionGroup group = stored.get(ids.groupId(entry.getKey()));
                        return group != null && entry.getValue().equals(new HashSet<>(nonNull(group.getPermissions())));
                    });
                    if (inStep) {
                        return Mono.empty();
                    }
                    log.info("Roles: the role definitions changed; bringing the role groups and the apps of the"
                            + " default workspace in line");
                    return reconcileApplications(ids)
                            .then(reconcileWorkspace(ids, desired))
                            .then(Flux.fromIterable(desired.entrySet())
                                    .concatMap(entry -> permissionGroupRepository.updateById(
                                            ids.groupId(entry.getKey()),
                                            Bridge.update()
                                                    .set(PermissionGroup.Fields.permissions, entry.getValue())
                                                    .set(
                                                            PermissionGroup.Fields.description,
                                                            entry.getKey().getDescription())))
                                    .then());
                });
    }

    private Mono<Void> reconcileApplications(DefaultWorkspaceIds ids) {
        Allowed allowed = allowedFor(ids);
        return applicationRepository
                .queryBuilder()
                .criteria(Bridge.equal(Application.Fields.workspaceId, ids.workspaceId()))
                .all()
                .concatMap(application -> {
                    String applicationId = application.getId();
                    List<String> themeIds = Stream.of(
                                    application.getEditModeThemeId(), application.getPublishedModeThemeId())
                            .filter(StringUtils::hasText)
                            .toList();
                    Flux<Theme> themes = themeRepository
                            .queryBuilder()
                            .criteria(Bridge.<Theme>or(
                                    Bridge.equal(Theme.Fields.applicationId, applicationId),
                                    Bridge.in(Theme.Fields.id, themeIds)))
                            .all()
                            .filter(theme -> !Boolean.TRUE.equals(theme.isSystemTheme()));
                    return strip(
                                    newPageRepository,
                                    newPageRepository
                                            .queryBuilder()
                                            .criteria(Bridge.equal(NewPage.Fields.applicationId, applicationId))
                                            .all(),
                                    allowed.page())
                            .then(strip(
                                    newActionRepository,
                                    newActionRepository
                                            .queryBuilder()
                                            .criteria(Bridge.equal(NewAction.Fields.applicationId, applicationId))
                                            .all(),
                                    allowed.action()))
                            .then(strip(
                                    actionCollectionRepository,
                                    actionCollectionRepository
                                            .queryBuilder()
                                            .criteria(
                                                    Bridge.equal(ActionCollection.Fields.applicationId, applicationId))
                                            .all(),
                                    allowed.action()))
                            .then(strip(themeRepository, themes, allowed.theme()))
                            .then(strip(applicationRepository, Flux.just(application), allowed.application()));
                })
                .then();
    }

    private Mono<Void> reconcileWorkspace(DefaultWorkspaceIds ids, Map<InstanceRole, Set<Permission>> desired) {
        Set<String> roleGroupIds = Set.copyOf(ids.groupIds().values());
        return workspaceRepository
                .findById(ids.workspaceId())
                .flatMap(workspace -> {
                    Map<String, Policy> policies = new HashMap<>();
                    nonNull(workspace.getPolicyMap()).forEach((permission, policy) -> {
                        Set<String> groups = new HashSet<>(nonNull(policy.getPermissionGroups()));
                        groups.removeAll(roleGroupIds);
                        policies.put(
                                permission,
                                Policy.builder()
                                        .permission(permission)
                                        .permissionGroups(groups)
                                        .build());
                    });
                    workspace.setPolicies(Set.copyOf(policies.values()));
                    desired.forEach((role, permissions) -> {
                        PermissionGroup group = new PermissionGroup();
                        group.setId(ids.groupId(role));
                        group.setPermissions(permissions);
                        policySolution.addPoliciesToExistingObject(
                                policySolution.generatePolicyFromPermissionGroupForObject(group, ids.workspaceId()),
                                workspace);
                    });
                    return workspaceRepository.updateByIdWithoutPermissionCheck(
                            workspace.getId(), AppGrantService.policiesUpdate(workspace.getPolicyMap()));
                })
                .then();
    }

    /**
     * Removes each role group from the policies its role does not give on these documents, and saves the policies of
     * the documents that changed.
     */
    private <T extends BaseDomain> Mono<Void> strip(
            AppsmithRepository<T> repository, Flux<T> documents, Map<String, Set<String>> allowedByGroup) {
        return documents
                .concatMap(document -> {
                    Map<String, Policy> policyMap = new HashMap<>(nonNull(document.getPolicyMap()));
                    boolean changed = false;
                    for (Map.Entry<String, Policy> entry : policyMap.entrySet()) {
                        Set<String> groups =
                                new HashSet<>(nonNull(entry.getValue().getPermissionGroups()));
                        boolean removed = groups.removeIf(groupId -> allowedByGroup.containsKey(groupId)
                                && !allowedByGroup.get(groupId).contains(entry.getKey()));
                        if (removed) {
                            changed = true;
                            entry.setValue(Policy.builder()
                                    .permission(entry.getKey())
                                    .permissionGroups(groups)
                                    .build());
                        }
                    }
                    if (!changed) {
                        return Mono.empty();
                    }
                    return repository.updateByIdWithoutPermissionCheck(
                            document.getId(), AppGrantService.policiesUpdate(policyMap));
                })
                .then();
    }

    /**
     * Per role group, the permissions its role gives on an app, a page, a query or JS object, and a theme, derived
     * through the policy graph as CE derives them when the document is created.
     */
    private Allowed allowedFor(DefaultWorkspaceIds ids) {
        Allowed allowed = new Allowed(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        for (InstanceRole role : InstanceRole.values()) {
            String groupId = ids.groupId(role);
            Set<Policy> workspacePolicies = role.getWorkspacePermissions().stream()
                    .filter(permission -> permission.getEntity().equals(Workspace.class))
                    .flatMap(permission ->
                            policySolution
                                    .generatePolicyFromPermissionWithPermissionGroup(permission, groupId)
                                    .values()
                                    .stream())
                    .collect(Collectors.toSet());
            Set<Policy> application =
                    policyGenerator.getAllChildPolicies(workspacePolicies, Workspace.class, Application.class);
            Set<Policy> page = policyGenerator.getAllChildPolicies(application, Application.class, NewPage.class);
            Set<Policy> action = policyGenerator.getAllChildPolicies(page, NewPage.class, NewAction.class);
            Set<Policy> theme = policyGenerator.getAllChildPolicies(application, Application.class, Theme.class);
            allowed.application().put(groupId, permissionsOf(application));
            allowed.page().put(groupId, permissionsOf(page));
            allowed.action().put(groupId, permissionsOf(action));
            allowed.theme().put(groupId, permissionsOf(theme));
        }
        return allowed;
    }

    private static Set<String> permissionsOf(Set<Policy> policies) {
        return policies.stream().map(Policy::getPermission).collect(Collectors.toSet());
    }

    private static <T> Set<T> nonNull(Set<T> set) {
        return set == null ? Set.of() : set;
    }

    private static <K, V> Map<K, V> nonNull(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    private record Allowed(
            Map<String, Set<String>> application,
            Map<String, Set<String>> page,
            Map<String, Set<String>> action,
            Map<String, Set<String>> theme) {}
}

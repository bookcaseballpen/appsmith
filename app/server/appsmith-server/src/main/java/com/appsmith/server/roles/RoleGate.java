package com.appsmith.server.roles;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.grants.GrantGroups;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.PermissionGroupRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * App grants go to teams, and a team may hold USER-role members. A USER never edits, so for a USER the grant groups
 * (teams and their personal group, see {@link GrantGroups}) count only for reading and executing apps, pages, queries,
 * JS objects and themes: every other permission check on those documents leaves them out. Groups of any other kind are
 * unchanged, so a USER keeps what a CE workspace role gives them in another workspace.
 *
 * <p>Applied where every repository resolves the caller's groups for a permission check
 * ({@code BaseAppsmithRepositoryCEImpl.getCurrentUserPermissionGroupsIfRequired}).
 */
@Component
public class RoleGate {

    private static final Set<Class<?>> APP_DOCUMENTS =
            Set.of(Application.class, NewPage.class, NewAction.class, Theme.class);

    private final DefaultWorkspaceService defaultWorkspaceService;
    private final PermissionGroupRepository permissionGroupRepository;

    // Lazy: every repository depends on this gate, and both of these depend on repositories.
    public RoleGate(
            @Lazy DefaultWorkspaceService defaultWorkspaceService,
            @Lazy PermissionGroupRepository permissionGroupRepository) {
        this.defaultWorkspaceService = defaultWorkspaceService;
        this.permissionGroupRepository = permissionGroupRepository;
    }

    /**
     * The caller's groups that may give {@code permission}: all of them, except the grant groups when the caller is a
     * USER and the permission changes an app.
     */
    public Mono<Set<String>> groupsFor(Set<String> permissionGroups, AclPermission permission) {
        String userRoleGroupId = defaultWorkspaceService
                .getResolvedIds()
                .map(ids -> ids.groupId(InstanceRole.USER))
                .orElse(null);
        if (permission == null
                || !changesApp(permission)
                || userRoleGroupId == null
                || permissionGroups == null
                || !permissionGroups.contains(userRoleGroupId)) {
            return Mono.justOrEmpty(permissionGroups);
        }
        return permissionGroupRepository
                .queryBuilder()
                .criteria(Bridge.<PermissionGroup>in(PermissionGroup.Fields.id, permissionGroups)
                        .in(PermissionGroup.Fields.defaultDomainType, GrantGroups.TYPES))
                .fields(PermissionGroup.Fields.id)
                .all()
                .map(PermissionGroup::getId)
                .collect(Collectors.toSet())
                .map(grantGroups -> {
                    Set<String> others = new HashSet<>(permissionGroups);
                    others.removeAll(grantGroups);
                    return others;
                });
    }

    static boolean changesApp(AclPermission permission) {
        String value = permission.getValue();
        return APP_DOCUMENTS.contains(permission.getEntity())
                && !value.startsWith("read:")
                && !value.startsWith("execute:");
    }
}

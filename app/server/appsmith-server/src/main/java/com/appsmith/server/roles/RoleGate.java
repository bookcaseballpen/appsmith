package com.appsmith.server.roles;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.domains.User;
import com.appsmith.server.grants.GrantGroups;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;

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
    private final CacheableRepositoryHelper cacheableRepositoryHelper;

    // Lazy: every repository depends on this gate, and the workspace service depends on repositories.
    public RoleGate(
            @Lazy DefaultWorkspaceService defaultWorkspaceService,
            CacheableRepositoryHelper cacheableRepositoryHelper) {
        this.defaultWorkspaceService = defaultWorkspaceService;
        this.cacheableRepositoryHelper = cacheableRepositoryHelper;
    }

    /**
     * The caller's groups that may give {@code permission}: all of them, except the grant groups when the caller is a
     * USER and the permission changes an app. The caller's grant groups are cached with their permission groups and
     * cleared with them on every membership or grant change.
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
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> (User) context.getAuthentication().getPrincipal())
                .flatMap(cacheableRepositoryHelper::getGrantGroupsOfUser)
                .defaultIfEmpty(Set.of())
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

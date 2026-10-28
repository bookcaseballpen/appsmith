package com.appsmith.server.roles;

import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.UserUtils;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.services.SessionUserService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Resolves and changes a user's {@link InstanceRole}, and gates the endpoints that are not covered by document
 * policies (AI, MCP tokens, user administration).
 *
 * <p>The role is derived, not stored: an instance administrator is ADMIN, anyone else gets the role of the default
 * workspace group they belong to, and a user in none of them is USER.
 */
@Service
public class InstanceRoleService {

    private static final String MANAGE_ROLES_ACTION = "Manage user roles";

    private final DefaultWorkspaceService defaultWorkspaceService;
    private final PermissionGroupRepository permissionGroupRepository;
    private final UserRepository userRepository;
    private final UserUtils userUtils;
    private final SessionUserService sessionUserService;

    public InstanceRoleService(
            DefaultWorkspaceService defaultWorkspaceService,
            PermissionGroupRepository permissionGroupRepository,
            UserRepository userRepository,
            UserUtils userUtils,
            SessionUserService sessionUserService) {
        this.defaultWorkspaceService = defaultWorkspaceService;
        this.permissionGroupRepository = permissionGroupRepository;
        this.userRepository = userRepository;
        this.userUtils = userUtils;
        this.sessionUserService = sessionUserService;
    }

    public Mono<InstanceRole> getRole(User user) {
        if (user == null || user.getId() == null || user.isAnonymous()) {
            return Mono.just(InstanceRole.USER);
        }
        return userUtils.isSuperUser(user).flatMap(isSuperUser -> {
            if (Boolean.TRUE.equals(isSuperUser)) {
                return Mono.just(InstanceRole.ADMIN);
            }
            return Mono.zip(
                            defaultWorkspaceService.getIds(),
                            permissionGroupRepository.getAllPermissionGroupsIdsForUser(user))
                    .map(tuple -> tuple.getT1().roleOf(tuple.getT2()));
        });
    }

    public Mono<InstanceRole> getCurrentUserRole() {
        return sessionUserService.getCurrentUser().flatMap(this::getRole).defaultIfEmpty(InstanceRole.USER);
    }

    /**
     * Completes when the user holds one of the allowed roles, errors with ACTION_IS_NOT_AUTHORIZED otherwise.
     */
    public Mono<Void> requireRole(User user, String action, InstanceRole... allowed) {
        return getRole(user).flatMap(role -> checkRole(role, action, allowed));
    }

    public Mono<Void> requireCurrentUserRole(String action, InstanceRole... allowed) {
        return getCurrentUserRole().flatMap(role -> checkRole(role, action, allowed));
    }

    public Mono<Boolean> isCurrentUserAdmin() {
        return getCurrentUserRole().map(role -> role == InstanceRole.ADMIN);
    }

    /**
     * Gives the user the role: ADMIN makes them an instance administrator and puts them in the default workspace's
     * administrator group; any other role removes instance administration and moves them to that role's group.
     */
    public Mono<InstanceRole> setRole(User user, InstanceRole role) {
        Mono<Boolean> instanceAdministration = role == InstanceRole.ADMIN
                // Also puts the user in the workspace administrator group, see UserUtils.
                ? userUtils.makeInstanceAdministrator(List.of(user))
                : userUtils.removeInstanceAdmin(List.of(user));
        Mono<Void> membership =
                role == InstanceRole.ADMIN ? Mono.empty() : defaultWorkspaceService.assignRole(user, role);
        return instanceAdministration.then(membership).thenReturn(role);
    }

    /**
     * Every active user with their role, for the administrators' role page.
     */
    public Mono<List<InstanceRoleUserDTO>> listUsers() {
        return requireCurrentUserRole(MANAGE_ROLES_ACTION, InstanceRole.ADMIN)
                .thenMany(userRepository.retrieveAll())
                .filter(user -> user.getDeletedAt() == null && !user.isAnonymous())
                .concatMap(user -> getRole(user).map(role -> InstanceRoleUserDTO.of(user, role)))
                .sort(Comparator.comparing(dto -> dto.email() == null ? "" : dto.email()))
                .collectList();
    }

    /**
     * An administrator changes another user's role. Administrators cannot change their own role, so the last
     * administrator cannot lock everyone out.
     */
    public Mono<InstanceRoleUserDTO> setRoleAsAdmin(String userId, InstanceRole role) {
        if (role == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "role"));
        }
        return requireCurrentUserRole(MANAGE_ROLES_ACTION, InstanceRole.ADMIN)
                .then(sessionUserService.getCurrentUser())
                .zipWith(userRepository
                        .findById(userId)
                        .switchIfEmpty(Mono.error(
                                new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.USER, userId))))
                .flatMap(tuple -> {
                    User admin = tuple.getT1();
                    User target = tuple.getT2();
                    if (target.getId().equals(admin.getId())) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, "Change your own role"));
                    }
                    return setRole(target, role).map(newRole -> InstanceRoleUserDTO.of(target, newRole));
                });
    }

    private static Mono<Void> checkRole(InstanceRole role, String action, InstanceRole... allowed) {
        if (Set.copyOf(Arrays.asList(allowed)).contains(role)) {
            return Mono.empty();
        }
        return Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, action));
    }
}

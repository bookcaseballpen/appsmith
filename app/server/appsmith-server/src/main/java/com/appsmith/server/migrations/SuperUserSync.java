package com.appsmith.server.migrations;

import com.appsmith.external.models.Policy;
import com.appsmith.server.constants.EnvVariables;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Organization;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.Permission;
import com.appsmith.server.helpers.CollectionUtils;
import com.appsmith.server.helpers.TextUtils;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.repositories.ConfigRepository;
import com.appsmith.server.repositories.OrganizationRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.appsmith.server.acl.AclPermission.MANAGE_USERS;
import static com.appsmith.server.acl.AclPermission.READ_USERS;
import static com.appsmith.server.acl.AclPermission.RESET_PASSWORD_USERS;

/**
 * Fork: applies {@code APPSMITH_ADMIN_EMAILS} at every start, as Mongock's {@code Migration10000_UpdateSuperUser} did
 * (runAlways). The listed users become exactly the members of the instance administrator role and of the default
 * organization's administrator role; a listed email without an account gets a disabled user, which becomes usable
 * once that person signs up. Users whose membership changed have their permission caches evicted.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SuperUserSync implements ApplicationRunner {

    private final ConfigRepository configRepository;
    private final OrganizationRepository organizationRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        sync(System.getenv(String.valueOf(EnvVariables.APPSMITH_ADMIN_EMAILS))).block();
    }

    public Mono<Set<String>> sync(String adminEmailsCsv) {
        final Set<String> emails = TextUtils.csvToSet(adminEmailsCsv).stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasLength)
                .collect(Collectors.toSet());

        final Mono<PermissionGroup> instanceAdminRoleMono = configRepository
                .findByName(FieldName.INSTANCE_CONFIG)
                .flatMap(config -> permissionGroupRepository.findById(
                        (String) config.getConfig().get(FieldName.DEFAULT_PERMISSION_GROUP)));

        return Mono.zip(instanceAdminRoleMono, organizationRepository.findBySlug(FieldName.DEFAULT))
                .flatMap(tuple -> {
                    final PermissionGroup instanceAdminRole = tuple.getT1();
                    final Organization organization = tuple.getT2();
                    return Flux.fromIterable(emails)
                            .concatMap(email -> userRepository
                                    .findByEmailAndOrganizationId(email, organization.getId())
                                    .switchIfEmpty(Mono.defer(() -> createUser(email, organization))))
                            .map(User::getId)
                            .collect(Collectors.toSet())
                            .flatMap(userIds -> assign(instanceAdminRole, organization, userIds));
                })
                .doOnNext(userIds -> log.info("Instance administrators set from APPSMITH_ADMIN_EMAILS: {}", userIds));
    }

    private Mono<Set<String>> assign(
            PermissionGroup instanceAdminRole, Organization organization, Set<String> userIds) {
        final Set<String> changedUserIds =
                CollectionUtils.findSymmetricDiff(instanceAdminRole.getAssignedToUserIds(), userIds);

        final Mono<Integer> organizationAdminRoleUpdate = permissionGroupRepository
                .findByDefaultDomainIdAndDefaultDomainType(organization.getId(), Organization.class.getSimpleName())
                .next()
                .flatMap(role -> permissionGroupRepository.updateById(role.getId(), assignedTo(userIds)));

        return permissionGroupRepository
                .updateById(instanceAdminRole.getId(), assignedTo(userIds))
                .then(organizationAdminRoleUpdate)
                .thenMany(userRepository.findAllById(changedUserIds))
                .concatMap(user -> permissionGroupRepository.evictAllPermissionGroupCachesForUser(
                        user.getEmail(), user.getOrganizationId()))
                .then(Mono.just(userIds));
    }

    private static BridgeUpdate assignedTo(Set<String> userIds) {
        final BridgeUpdate update = Bridge.update();
        update.set(PermissionGroup.Fields.assignedToUserIds, new HashSet<>(userIds));
        return update;
    }

    /** As {@code UpdateSuperUserMigrationHelperCE.createNewUser}: a disabled user with its user-management role. */
    private Mono<User> createUser(String email, Organization organization) {
        log.info("Creating super user with username {}", email);
        final User user = new User();
        user.setEmail(email);
        user.setIsEnabled(false);
        user.setOrganizationId(organization.getId());
        return userRepository.save(user).flatMap(savedUser -> {
            final PermissionGroup userManagementRole = new PermissionGroup();
            userManagementRole.setName(savedUser.getUsername() + FieldName.SUFFIX_USER_MANAGEMENT_ROLE);
            userManagementRole.setPermissions(Set.of(new Permission(savedUser.getId(), MANAGE_USERS)));
            userManagementRole.setAssignedToUserIds(Set.of(savedUser.getId()));
            return permissionGroupRepository.save(userManagementRole).flatMap(role -> {
                final Set<String> roleIds = Set.of(role.getId());
                savedUser.setPolicies(new HashSet<>(Set.of(
                        Policy.builder()
                                .permission(READ_USERS.getValue())
                                .permissionGroups(roleIds)
                                .build(),
                        Policy.builder()
                                .permission(MANAGE_USERS.getValue())
                                .permissionGroups(roleIds)
                                .build(),
                        Policy.builder()
                                .permission(RESET_PASSWORD_USERS.getValue())
                                .permissionGroups(roleIds)
                                .build())));
                return userRepository.save(savedUser);
            });
        });
    }
}

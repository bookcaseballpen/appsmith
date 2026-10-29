package com.appsmith.server.migrations;

import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Organization;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.repositories.ConfigRepository;
import com.appsmith.server.repositories.OrganizationRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SuperUserSyncTest {

    @Autowired
    SuperUserSync superUserSync;

    @Autowired
    ConfigRepository configRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    PermissionGroupRepository permissionGroupRepository;

    @Autowired
    UserRepository userRepository;

    private Set<String> instanceAdminsBefore;
    private Set<String> organizationAdminsBefore;

    private PermissionGroup instanceAdminRole() {
        return configRepository
                .findByName(FieldName.INSTANCE_CONFIG)
                .flatMap(config -> permissionGroupRepository.findById(
                        (String) config.getConfig().get(FieldName.DEFAULT_PERMISSION_GROUP)))
                .block();
    }

    private Organization organization() {
        return organizationRepository.findBySlug(FieldName.DEFAULT).block();
    }

    private PermissionGroup organizationAdminRole() {
        return permissionGroupRepository
                .findByDefaultDomainIdAndDefaultDomainType(organization().getId(), Organization.class.getSimpleName())
                .next()
                .block();
    }

    private static Set<String> members(PermissionGroup role) {
        return role.getAssignedToUserIds() == null ? Set.of() : new HashSet<>(role.getAssignedToUserIds());
    }

    @BeforeEach
    void rememberAdmins() {
        instanceAdminsBefore = members(instanceAdminRole());
        organizationAdminsBefore = members(organizationAdminRole());
    }

    @AfterEach
    void restoreAdmins() {
        final BridgeUpdate instance = Bridge.update();
        instance.set(PermissionGroup.Fields.assignedToUserIds, instanceAdminsBefore);
        permissionGroupRepository.updateById(instanceAdminRole().getId(), instance).block();
        final BridgeUpdate organization = Bridge.update();
        organization.set(PermissionGroup.Fields.assignedToUserIds, organizationAdminsBefore);
        permissionGroupRepository
                .updateById(organizationAdminRole().getId(), organization)
                .block();
    }

    @Test
    void should_makeListedUsersExactlyTheAdmins_when_emailsListExistingAndNewUsers() {
        // Given
        final User existing = new User();
        existing.setEmail("sync-" + UUID.randomUUID() + "@example.com");
        existing.setOrganizationId(organization().getId());
        final User saved = userRepository.save(existing).block();
        final String newEmail = "new-" + UUID.randomUUID() + "@example.com";

        // When
        final Set<String> userIds = superUserSync
                .sync(" " + saved.getEmail().toUpperCase() + " , " + newEmail)
                .block();

        // Then
        final User created = userRepository
                .findByEmailAndOrganizationId(newEmail, organization().getId())
                .block();
        assertThat(created).isNotNull();
        assertThat(created.getIsEnabled()).isFalse();
        assertThat(userIds).containsExactlyInAnyOrder(saved.getId(), created.getId());
        assertThat(members(instanceAdminRole())).containsExactlyInAnyOrder(saved.getId(), created.getId());
        assertThat(members(organizationAdminRole())).containsExactlyInAnyOrder(saved.getId(), created.getId());
    }

    @Test
    void should_removeAllAdmins_when_variableIsEmpty() {
        // Given
        final User user = new User();
        user.setEmail("sync-" + UUID.randomUUID() + "@example.com");
        user.setOrganizationId(organization().getId());
        final User saved = userRepository.save(user).block();
        superUserSync.sync(saved.getEmail()).block();

        // When
        superUserSync.sync("").block();

        // Then
        assertThat(members(instanceAdminRole())).isEmpty();
        assertThat(members(organizationAdminRole())).isEmpty();
    }
}

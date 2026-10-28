package com.appsmith.server.roles;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.LoginSource;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.Permission;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext
class DefaultWorkspaceServiceTest {

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    PermissionGroupRepository permissionGroupRepository;

    @Autowired
    UserService userService;

    @Test
    void should_reuseStoredWorkspaceAndGroups_when_bootstrapRunsAgain() {
        // Given
        DefaultWorkspaceIds first =
                defaultWorkspaceService.ensureDefaultWorkspace().block();

        // When
        DefaultWorkspaceIds second =
                defaultWorkspaceService.ensureDefaultWorkspace().block();

        // Then
        assertThat(second).isEqualTo(first);
        assertThat(defaultWorkspaceService.getIds().block()).isEqualTo(first);
        List<PermissionGroup> groups = permissionGroupRepository
                .findByDefaultDomainIdAndDefaultDomainType(first.workspaceId(), Workspace.class.getSimpleName())
                .collectList()
                .block();
        assertThat(groups)
                .extracting(PermissionGroup::getId)
                .containsExactlyInAnyOrderElementsOf(first.groupIds().values());
        Workspace workspace = workspaceRepository.findById(first.workspaceId()).block();
        assertThat(workspace.getName()).isEqualTo(DefaultWorkspaceService.DEFAULT_WORKSPACE_NAME);
        assertThat(workspace.getDefaultPermissionGroups())
                .containsExactlyInAnyOrderElementsOf(first.groupIds().values());
    }

    @Test
    void should_giveEachRoleGroupItsWorkspacePermissions_when_bootstrapped() {
        // Given
        DefaultWorkspaceIds ids = defaultWorkspaceService.getIds().block();

        for (InstanceRole role : InstanceRole.values()) {
            // When
            PermissionGroup group =
                    permissionGroupRepository.findById(ids.groupId(role)).block();
            Set<AclPermission> workspacePermissions = group.getPermissions().stream()
                    .filter(permission -> ids.workspaceId().equals(permission.getDocumentId()))
                    .map(Permission::getAclPermission)
                    .collect(Collectors.toSet());

            // Then
            assertThat(workspacePermissions).isEqualTo(role.getWorkspacePermissions());
        }
    }

    @Test
    void should_joinDefaultWorkspaceAsUserWithoutOwnWorkspace_when_userSignsUp() {
        // Given
        User newUser = new User();
        newUser.setEmail("signup-" + UUID.randomUUID() + "@roles.test");
        newUser.setPassword("a-long-password-1");
        newUser.setSource(LoginSource.FORM);

        // When
        User created = userService.create(newUser).block();

        // Then
        DefaultWorkspaceIds ids = defaultWorkspaceService.getIds().block();
        PermissionGroup userGroup = permissionGroupRepository
                .findById(ids.groupId(InstanceRole.USER))
                .block();
        assertThat(userGroup.getAssignedToUserIds()).contains(created.getId());
        assertThat(instanceRoleService.getRole(created).block()).isEqualTo(InstanceRole.USER);
        assertThat(permissionGroupRepository
                        .findAllByAssignedToUserIn(Set.of(created.getId()), Optional.empty(), Optional.empty())
                        .filter(group -> Workspace.class.getSimpleName().equals(group.getDefaultDomainType()))
                        .map(PermissionGroup::getDefaultDomainId)
                        .collect(Collectors.toSet())
                        .block())
                .containsExactly(ids.workspaceId());
    }
}

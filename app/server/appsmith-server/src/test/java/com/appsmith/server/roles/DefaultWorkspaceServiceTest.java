package com.appsmith.server.roles;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Config;
import com.appsmith.server.domains.LoginSource;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.Permission;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.repositories.ConfigRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.services.UserService;
import com.appsmith.server.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// A context of its own: a cached context keeps the ids it bootstrapped with, and another class's context may have
// replaced the stored workspace since then in the shared database.
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
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

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    ConfigRepository configRepository;

    Config storedIds;

    @BeforeEach
    void rememberStoredIds() {
        storedIds =
                configRepository.findByName(DefaultWorkspaceService.CONFIG_NAME).block();
    }

    @AfterEach
    void restoreStoredIds() {
        // Tests that bootstrap from scratch leave other ids stored; put back the ones the context started with.
        configRepository
                .findByName(DefaultWorkspaceService.CONFIG_NAME)
                .flatMap(configRepository::delete)
                .then(Mono.defer(() -> configRepository.save(storedIds)))
                .block();
    }

    @Test
    void should_settleOnOneWorkspace_when_twoBootstrapsRaceOnFreshDatabase() {
        // Given
        configRepository.delete(storedIds).block();

        // When
        Tuple2<DefaultWorkspaceIds, DefaultWorkspaceIds> results = Mono.zip(
                        defaultWorkspaceService.ensureDefaultWorkspace().subscribeOn(Schedulers.parallel()),
                        defaultWorkspaceService.ensureDefaultWorkspace().subscribeOn(Schedulers.parallel()))
                .block();

        // Then
        DefaultWorkspaceIds stored =
                defaultWorkspaceService.ensureDefaultWorkspace().block();
        assertThat(results.getT1()).isEqualTo(stored);
        assertThat(results.getT2()).isEqualTo(stored);
        assertThat(workspaceRepository.findById(stored.workspaceId()).block().getDeletedAt())
                .isNull();
    }

    @Test
    void should_createDefaultWorkspaceNextToExistingOnes_when_databaseIsNotFresh() {
        // Given
        Workspace legacy = new Workspace();
        legacy.setName("Legacy " + UUID.randomUUID());
        legacy = workspaceRepository.save(legacy).block();
        configRepository.delete(storedIds).block();

        // When
        DefaultWorkspaceIds ids =
                defaultWorkspaceService.ensureDefaultWorkspace().block();

        // Then
        assertThat(ids.workspaceId()).isNotEqualTo(legacy.getId());
        assertThat(workspaceRepository.findById(ids.workspaceId()).block().getName())
                .isEqualTo(DefaultWorkspaceService.DEFAULT_WORKSPACE_NAME);
        Workspace legacyAfter = workspaceRepository.findById(legacy.getId()).block();
        assertThat(legacyAfter.getDeletedAt()).isNull();
        assertThat(legacyAfter.getName()).isEqualTo(legacy.getName());
    }

    @Test
    void should_refuseDeletingDefaultWorkspace_when_adminDeletesIt() {
        // Given
        String workspaceId = defaultWorkspaceService.getIds().block().workspaceId();

        // When / Then
        StepVerifier.create(workspaceService.archiveById(workspaceId))
                .expectErrorMatches(error -> error instanceof AppsmithException appsmithException
                        && appsmithException.getError() == AppsmithError.ACTION_IS_NOT_AUTHORIZED)
                .verify();
    }

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

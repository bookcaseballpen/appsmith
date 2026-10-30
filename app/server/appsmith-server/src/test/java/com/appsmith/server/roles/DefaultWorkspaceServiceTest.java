package com.appsmith.server.roles;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.appsmith.external.models.BaseDomain;
import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.Config;
import com.appsmith.server.domains.LoginSource;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.Permission;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.grants.AppGrantDTO;
import com.appsmith.server.grants.AppGrantService;
import com.appsmith.server.grants.GrantLevel;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.AppsmithRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.ConfigRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.PluginRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.UserService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.solutions.PolicySolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    ConfigRepository configRepository;

    @Autowired
    RoleGroupReconciler roleGroupReconciler;

    @Autowired
    AppGrantService appGrantService;

    @Autowired
    ApplicationPageService applicationPageService;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    NewPageRepository newPageRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    @Autowired
    PluginRepository pluginRepository;

    @Autowired
    PolicySolution policySolution;

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
    void should_takeAwayOldRoleAccessButKeepGrants_when_roleDefinitionsChanged() {
        // Given
        // An instance from before app grants: DEV managed and USER read every app through their role group.
        DefaultWorkspaceIds ids = defaultWorkspaceService.getIds().block();
        String devGroupId = ids.groupId(InstanceRole.DEV);
        String userGroupId = ids.groupId(InstanceRole.USER);
        String adminGroupId = ids.groupId(InstanceRole.ADMIN);
        User admin = createUserWithRole(InstanceRole.ADMIN);
        User grantedDev = createUserWithRole(InstanceRole.DEV);
        Application unsaved = new Application();
        unsaved.setName("reconcile-" + UUID.randomUUID());
        Application application = runAs(applicationPageService.createApplication(unsaved, ids.workspaceId()), admin)
                .block();
        runAs(
                        appGrantService.setGrant(
                                application.getId(),
                                new AppGrantDTO.Request(
                                        new AppGrantDTO.Grantee(AppGrantDTO.Type.USER, grantedDev.getId()),
                                        GrantLevel.EDIT)),
                        admin)
                .block();
        String pageId = application.getPages().get(0).getId();
        addGroup(applicationRepository, application.getId(), devGroupId, "manage:applications", "read:applications");
        addGroup(applicationRepository, application.getId(), userGroupId, "read:applications");
        addGroup(newPageRepository, pageId, devGroupId, "manage:pages", "read:pages");
        PermissionGroup devGroup =
                permissionGroupRepository.findById(devGroupId).block();
        Set<Permission> oldPermissions = new HashSet<>(devGroup.getPermissions());
        oldPermissions.add(new Permission(ids.workspaceId(), AclPermission.WORKSPACE_MANAGE_APPLICATIONS));
        oldPermissions.add(new Permission(ids.workspaceId(), AclPermission.WORKSPACE_READ_APPLICATIONS));
        permissionGroupRepository
                .updateById(devGroupId, Bridge.update().set(PermissionGroup.Fields.permissions, oldPermissions))
                .block();

        // When
        roleGroupReconciler.reconcile(ids).block();
        roleGroupReconciler.reconcile(ids).block();

        // Then
        Map<String, Policy> app =
                applicationRepository.findById(application.getId()).block().getPolicyMap();
        assertThat(app.get("manage:applications").getPermissionGroups())
                .doesNotContain(devGroupId)
                .contains(adminGroupId);
        assertThat(app.get("read:applications").getPermissionGroups()).doesNotContain(devGroupId, userGroupId);
        Map<String, Policy> page = newPageRepository.findById(pageId).block().getPolicyMap();
        assertThat(page.get("manage:pages").getPermissionGroups()).doesNotContain(devGroupId);
        assertThat(runAs(appGrantService.getGrants(application.getId()), admin).block())
                .singleElement()
                .satisfies(grant -> assertThat(grant.level()).isEqualTo(GrantLevel.EDIT));
        assertThat(permissionGroupRepository.findById(devGroupId).block().getPermissions().stream()
                        .filter(permission -> ids.workspaceId().equals(permission.getDocumentId()))
                        .map(Permission::getAclPermission)
                        .collect(Collectors.toSet()))
                .isEqualTo(InstanceRole.DEV.getWorkspacePermissions());
        assertThat(workspaceRepository
                        .findById(ids.workspaceId())
                        .block()
                        .getPolicyMap()
                        .get(AclPermission.WORKSPACE_MANAGE_APPLICATIONS.getValue())
                        .getPermissionGroups())
                .doesNotContain(devGroupId)
                .contains(adminGroupId);
    }

    @Test
    void should_logMarkedError_when_reconcileFailsAtStartup() {
        // Given
        RoleGroupReconciler failingReconciler = Mockito.mock(RoleGroupReconciler.class);
        Mockito.when(failingReconciler.reconcile(Mockito.any()))
                .thenReturn(Mono.error(new IllegalStateException("database went away")));
        DefaultWorkspaceService service = new DefaultWorkspaceService(
                configRepository,
                workspaceRepository,
                permissionGroupRepository,
                pluginRepository,
                policySolution,
                cacheableRepositoryHelper,
                failingReconciler);
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultWorkspaceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // When
        try {
            service.bootstrapOnStartup();
        } finally {
            logger.detachAppender(appender);
        }

        // Then
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage())
                            .isEqualTo(DefaultWorkspaceService.RECONCILE_FAILED_MESSAGE)
                            .startsWith("ROLES RECONCILE FAILED")
                            .contains("may be out of date", "retry on the next start")
                            .doesNotContain("\n");
                    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("database went away");
                });
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

    /** Adds the group to the document's policies for these permissions, as an older version of the role stored it. */
    private <T extends BaseDomain> void addGroup(
            AppsmithRepository<T> repository, String id, String groupId, String... permissions) {
        T document = repository.queryBuilder().byId(id).one().block();
        Map<String, Policy> policyMap = new HashMap<>(document.getPolicyMap());
        for (String permission : permissions) {
            Set<String> groups = new HashSet<>(
                    policyMap.containsKey(permission)
                            ? policyMap.get(permission).getPermissionGroups()
                            : Set.of());
            groups.add(groupId);
            policyMap.put(
                    permission,
                    Policy.builder()
                            .permission(permission)
                            .permissionGroups(groups)
                            .build());
        }
        repository
                .updateByIdWithoutPermissionCheck(id, Bridge.update().set(BaseDomain.Fields.policyMap, policyMap))
                .block();
    }

    private User createUserWithRole(InstanceRole role) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@roles.example.com");
        user.setName(role.name());
        user.setIsEnabled(true);
        user.setOrganizationId(
                cacheableRepositoryHelper.getCurrentUserOrganizationId().block());
        User saved = userRepository.save(user).block();
        instanceRoleService.setRole(saved, role).block();
        return saved;
    }

    private static <T> Mono<T> runAs(Mono<T> publisher, User user) {
        return publisher.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))));
    }
}

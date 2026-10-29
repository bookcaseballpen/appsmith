package com.appsmith.server.roles;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionDTO;
import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStorageDTO;
import com.appsmith.external.models.Property;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.datasources.base.DatasourceService;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.fork.internal.ApplicationForkingService;
import com.appsmith.server.helpers.MockPluginExecutor;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.ratelimiting.RateLimitService;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.services.AIAssistantService;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.LayoutActionService;
import com.appsmith.server.services.McpTokenService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.solutions.EnvironmentPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * What each of the four roles can and cannot do in the default workspace, through the real services and, where the
 * HTTP layer matters (the approver filter), through the real security chain.
 */
@SpringBootTest
@DirtiesContext
class InstanceRolePermissionsTest {

    private static final String SECRET_HOST = "secret-host.example.com";
    private static final String SECRET_TOKEN = "Bearer top-secret-token";

    @Autowired
    ReactiveWebApplicationContext context;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    @Autowired
    ApplicationPageService applicationPageService;

    @Autowired
    DatasourceService datasourceService;

    @Autowired
    PluginService pluginService;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    EnvironmentPermission environmentPermission;

    @Autowired
    LayoutActionService layoutActionService;

    @Autowired
    AIAssistantService aiAssistantService;

    @Autowired
    McpTokenService mcpTokenService;

    @Autowired
    ApplicationService applicationService;

    @Autowired
    ApplicationForkingService applicationForkingService;

    @Autowired
    ImportService importService;

    @MockBean
    PluginExecutorHelper pluginExecutorHelper;

    @MockBean
    RateLimitService rateLimitService;

    WebTestClient webTestClient;
    Map<InstanceRole, User> users;
    String workspaceId;
    String environmentId;
    Plugin plugin;
    Application application;
    String pageId;
    Datasource datasource;

    @BeforeEach
    void setUp() {
        Mockito.when(pluginExecutorHelper.getPluginExecutor(Mockito.any()))
                .thenReturn(Mono.just(new MockPluginExecutor()));
        Mockito.when(rateLimitService.tryIncreaseCounter(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(true));
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();

        workspaceId = defaultWorkspaceService.getIds().block().workspaceId();
        users = new EnumMap<>(InstanceRole.class);
        for (InstanceRole role : InstanceRole.values()) {
            users.put(role, createUserWithRole(role));
        }
        User admin = users.get(InstanceRole.ADMIN);

        environmentId = runAs(
                        workspaceService.getDefaultEnvironmentId(
                                workspaceId, environmentPermission.getExecutePermission()),
                        admin)
                .block();
        plugin = pluginService.findByPackageName("restapi-plugin").block();

        Application unsaved = new Application();
        unsaved.setName(unique("roles-app"));
        application = runAs(applicationPageService.createApplication(unsaved, workspaceId), admin)
                .block();
        pageId = application.getPages().get(0).getId();

        datasource = runAs(datasourceService.create(newDatasource()), admin).block();
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_returnRoleAndDefaultWorkspace_when_userFetchesProfile(InstanceRole role) {
        // When / Then
        clientFor(role)
                .get()
                .uri("/api/v1/users/me")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.role")
                .isEqualTo(role.name())
                .jsonPath("$.data.defaultWorkspaceId")
                .isEqualTo(workspaceId);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_createDatasourceOnlyAsAdmin_when_roleCreatesDatasource(InstanceRole role) {
        // Given
        Datasource unsaved = newDatasource();

        // When
        Boolean created = runAs(datasourceService.create(unsaved), users.get(role))
                .map(saved -> true)
                .onErrorResume(AppsmithException.class, error -> Mono.just(false))
                .block();

        // Then
        assertThat(created).isEqualTo(role == InstanceRole.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_returnConnectionSettingsOnlyToAdmin_when_roleListsDatasources(InstanceRole role) {
        // When
        String body = new String(clientFor(role)
                .get()
                .uri("/api/v1/datasources?workspaceId={workspaceId}", workspaceId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());

        // Then
        if (role == InstanceRole.ADMIN) {
            assertThat(body).contains(SECRET_HOST).contains(SECRET_TOKEN);
        } else {
            assertThat(body).doesNotContain(SECRET_HOST).doesNotContain(SECRET_TOKEN);
        }
        if (role == InstanceRole.DEV) {
            // A developer still sees the datasource itself, to pick it in a query.
            assertThat(body).contains(datasource.getId()).contains(datasource.getName());
        }
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_testSavedDatasourceOnlyAsAdmin_when_roleTestsItWithOwnSettings(InstanceRole role) {
        // Given
        // A test with a saved id merges the stored credentials into the settings sent, so a non-admin could send
        // them to a host of their choice.
        DatasourceConfiguration configuration = new DatasourceConfiguration();
        configuration.setUrl("https://attacker.example.com");
        DatasourceStorageDTO storage = new DatasourceStorageDTO(datasource.getId(), environmentId, configuration);
        storage.setWorkspaceId(workspaceId);
        storage.setPluginId(plugin.getId());

        // When
        Optional<AppsmithError> error = runAs(datasourceService.testDatasource(storage, environmentId), users.get(role))
                .map(result -> Optional.<AppsmithError>empty())
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError())))
                .block();

        // Then
        assertThat(error.filter(AppsmithError.UNAUTHORIZED_ACCESS::equals).isPresent())
                .isEqualTo(role != InstanceRole.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_createQueryOnlyAsDevOrAdmin_when_roleCreatesQueryOnDatasource(InstanceRole role) {
        // Given
        ActionDTO action = new ActionDTO();
        action.setName("Query" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        action.setPageId(pageId);
        Datasource reference = new Datasource();
        reference.setId(datasource.getId());
        reference.setPluginId(plugin.getId());
        action.setDatasource(reference);
        ActionConfiguration actionConfiguration = new ActionConfiguration();
        actionConfiguration.setPath("/users");
        action.setActionConfiguration(actionConfiguration);

        // When
        Boolean created = runAs(layoutActionService.createSingleAction(action), users.get(role))
                .map(saved -> true)
                .onErrorResume(AppsmithException.class, error -> Mono.just(false))
                .block();

        // Then
        assertThat(created).isEqualTo(role.canEdit());
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_openEditorReadOnlyForApprover_when_roleReadsEditModePage(InstanceRole role) {
        // When / Then
        WebTestClient.ResponseSpec response =
                clientFor(role).get().uri("/api/v1/pages/{pageId}", pageId).exchange();
        if (role == InstanceRole.USER) {
            response.expectStatus().isNotFound();
        } else {
            response.expectStatus().isOk();
        }
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_editAppOnlyAsDevOrAdmin_when_roleRenamesApp(InstanceRole role) {
        // Given
        String newName = unique("renamed");

        // When
        WebTestClient.ResponseSpec response = clientFor(role)
                .put()
                .uri("/api/v1/applications/{id}", application.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", newName))
                .exchange();

        // Then
        // CE answers 200 with no data when the caller lacks edit permission, so a User's rename is checked by the
        // name it leaves behind rather than by status.
        if (role == InstanceRole.APPROVER) {
            response.expectStatus().isForbidden();
        } else {
            response.expectStatus().isOk();
        }
        String storedName =
                applicationRepository.findById(application.getId()).block().getName();
        assertThat(storedName.equals(newName)).isEqualTo(role.canEdit());
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_publishOnlyAsApproverOrAdmin_when_rolePublishesApp(InstanceRole role) {
        // When
        WebTestClient.ResponseSpec response = clientFor(role)
                .post()
                .uri("/api/v1/applications/publish/{id}", application.getId())
                .exchange();

        // Then
        if (role == InstanceRole.ADMIN || role == InstanceRole.APPROVER) {
            response.expectStatus().isOk();
        } else {
            response.expectStatus().is4xxClientError();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "DEV"})
    void should_duplicateAppWithoutMakingItLive_when_builderDuplicatesLiveApp(InstanceRole role) {
        // Given
        makeLive(application.getId());

        // When
        Application copy = runAs(applicationPageService.cloneApplication(application.getId()), users.get(role))
                .block();

        // Then
        Application stored = applicationRepository.findById(copy.getId()).block();
        assertThat(stored.getPublishedPages()).isNotEmpty();
        assertThat(stored.getLastDeployedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "DEV"})
    void should_forkAppOnlyAsAdmin_when_builderForksApp(InstanceRole role) {
        // When
        // Forking may copy datasources into the target workspace, so it needs the datasource create permission.
        Optional<String> forkedId = runAs(
                        applicationForkingService
                                .forkApplicationToWorkspace(application.getId(), workspaceId)
                                .map(imported ->
                                        Optional.of(imported.getApplication().getId())),
                        users.get(role))
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.empty()))
                .block();

        // Then
        assertThat(forkedId.isPresent()).isEqualTo(role == InstanceRole.ADMIN);
        forkedId.ifPresent(
                id -> assertThat(applicationRepository.findById(id).block().getLastDeployedAt())
                        .isNull());
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "DEV"})
    void should_publishInternallyOnLiveAppOnlyAsAdmin_when_serverRepublishesForRole(InstanceRole role) {
        // Given
        makeLive(application.getId());
        Application stored = applicationRepository.findById(application.getId()).block();
        stored.setUnpublishedAppLayout(new Application.AppLayout(Application.AppLayout.Type.MOBILE));
        applicationRepository.save(stored).block();

        // When
        runAs(applicationPageService.publishWithoutPermissionChecks(application.getId(), false), users.get(role))
                .block();

        // Then
        Application.AppLayout publishedLayout =
                applicationRepository.findById(application.getId()).block().getPublishedAppLayout();
        boolean republished = publishedLayout != null && publishedLayout.getType() == Application.AppLayout.Type.MOBILE;
        assertThat(republished).isEqualTo(role == InstanceRole.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "DEV"})
    void should_makeAppLiveOnlyAsAdmin_when_gitCommitPublishesManually(InstanceRole role) {
        // When
        // Git commit-and-deploy reaches the publish this way; it must not fail for a developer after the commit.
        runAs(applicationPageService.publishWithoutPermissionChecks(application.getId(), true), users.get(role))
                .block();

        // Then
        Application stored = applicationRepository.findById(application.getId()).block();
        assertThat(stored.getLastDeployedAt() != null).isEqualTo(role == InstanceRole.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "DEV"})
    void should_refuseImportOverLiveAppOnlyForDev_when_roleImportsOverIt(InstanceRole role) {
        // Given
        makeLive(application.getId());

        // When
        // An empty export is enough: the role check comes before the import reads it.
        Optional<AppsmithError> error = runAs(
                        importService
                                .updateNonGitConnectedArtifactFromJson(
                                        workspaceId, application.getId(), new ApplicationJson())
                                .map(imported -> Optional.<AppsmithError>empty()),
                        users.get(role))
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError())))
                .onErrorResume(exception -> Mono.just(Optional.empty()))
                .block();

        // Then
        assertThat(error.filter(AppsmithError.ACTION_IS_NOT_AUTHORIZED::equals).isPresent())
                .isEqualTo(role == InstanceRole.DEV);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_hideNeverPublishedAppOnlyFromUser_when_roleOpensOrListsIt(InstanceRole role) {
        // When
        boolean opens = canOpenInViewMode(role);
        boolean lists = listsApplication(role);

        // Then
        assertThat(opens).isEqualTo(role != InstanceRole.USER);
        assertThat(lists).isEqualTo(role != InstanceRole.USER);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_showAppToEveryRole_when_approverHasPublishedIt(InstanceRole role) {
        // Given
        runAs(applicationPageService.publish(application.getId(), true), users.get(InstanceRole.APPROVER))
                .block();

        // When
        boolean opens = canOpenInViewMode(role);
        boolean lists = listsApplication(role);

        // Then
        assertThat(opens).isTrue();
        assertThat(lists).isTrue();
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_deleteAppOnlyAsAdmin_when_roleDeletesApp(InstanceRole role) {
        // When
        Boolean deleted = runAs(applicationPageService.deleteApplication(application.getId()), users.get(role))
                .map(archived -> true)
                .onErrorResume(AppsmithException.class, error -> Mono.just(false))
                .block();

        // Then
        assertThat(deleted).isEqualTo(role == InstanceRole.ADMIN);
        assertThat(applicationRepository.findById(application.getId()).block() == null)
                .isEqualTo(role == InstanceRole.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_passAiGateOnlyForDevOrAdmin_when_roleCallsAiAssistant(InstanceRole role) {
        // When
        boolean denied =
                isDeniedByRoleGate(runAs(aiAssistantService.getAIResponse("OPENAI", "hello", null), users.get(role)));

        // Then
        assertThat(denied).isEqualTo(!role.canEdit());
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_passMcpGateOnlyForDevOrAdmin_when_roleIssuesMcpToken(InstanceRole role) {
        // When
        boolean denied = isDeniedByRoleGate(mcpTokenService.create(users.get(role), "token", 30));

        // Then
        assertThat(denied).isEqualTo(!role.canEdit());
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_createWorkspaceOnlyAsAdmin_when_roleCreatesWorkspace(InstanceRole role) {
        // Given
        Workspace unsaved = new Workspace();
        unsaved.setName(unique("roles-workspace"));

        // When
        Optional<AppsmithError> error = runAs(workspaceService.create(unsaved), users.get(role))
                .map(created -> Optional.<AppsmithError>empty())
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError())))
                .block();

        // Then
        if (role == InstanceRole.ADMIN) {
            assertThat(error).isEmpty();
        } else {
            assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
        }
    }

    @Test
    void should_createWorkspaceForGivenOwner_when_serverCallsWithoutSecurityContext() {
        // Given
        Workspace unsaved = new Workspace();
        unsaved.setName(unique("roles-internal-workspace"));

        // When
        Workspace created = workspaceService
                .create(unsaved, users.get(InstanceRole.USER), Boolean.FALSE)
                .block();

        // Then
        assertThat(created.getId()).isNotBlank();
        runAs(workspaceService.archiveById(created.getId()), users.get(InstanceRole.USER))
                .block();
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_neverCreatePersonalWorkspace_when_anyRoleAsksForOne(InstanceRole role) {
        // Given
        User user = users.get(role);

        // When
        Optional<AppsmithError> error = runAs(workspaceService.createDefault(new Workspace(), user), user)
                .map(created -> Optional.<AppsmithError>empty())
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError())))
                .block();

        // Then
        assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"DEV", "APPROVER", "USER"})
    void should_refuseRoleChange_when_callerIsNotAdmin(InstanceRole role) {
        // When / Then
        clientFor(role)
                .put()
                .uri(
                        "/api/v1/admin/users/{userId}/role",
                        users.get(InstanceRole.USER).getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "ADMIN"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_changeRole_when_adminSetsAnotherUsersRole(InstanceRole newRole) {
        // Given
        User target = createUserWithRole(InstanceRole.USER);

        // When
        clientFor(InstanceRole.ADMIN)
                .put()
                .uri("/api/v1/admin/users/{userId}/role", target.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", newRole.name()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.role")
                .isEqualTo(newRole.name());

        // Then
        assertThat(instanceRoleService
                        .getRole(userRepository.findById(target.getId()).block())
                        .block())
                .isEqualTo(newRole);
    }

    private void makeLive(String applicationId) {
        runAs(applicationPageService.publish(applicationId, true), users.get(InstanceRole.ADMIN))
                .block();
    }

    private boolean canOpenInViewMode(InstanceRole role) {
        // CE answers 200 with an empty body when the app cannot be read, so look for the app in the body.
        String body = clientFor(role)
                .get()
                .uri("/api/v1/applications/view/{id}", application.getId())
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return body != null && body.contains(application.getId());
    }

    private boolean listsApplication(InstanceRole role) {
        List<String> ids = runAs(
                        applicationService
                                .findByWorkspaceId(workspaceId, AclPermission.READ_APPLICATIONS)
                                .map(Application::getId)
                                .collectList(),
                        users.get(role))
                .block();
        return ids.contains(application.getId());
    }

    private static boolean isDeniedByRoleGate(Mono<?> call) {
        return Boolean.TRUE.equals(call.map(result -> false)
                .onErrorResume(error -> Mono.just(error instanceof AppsmithException appsmithException
                        && appsmithException.getError() == AppsmithError.ACTION_IS_NOT_AUTHORIZED))
                .defaultIfEmpty(false)
                .block());
    }

    private User createUserWithRole(InstanceRole role) {
        User user = new User();
        user.setEmail(unique(role.name().toLowerCase()) + "@roles.test");
        user.setName(role.name());
        user.setIsEnabled(true);
        user.setOrganizationId(
                cacheableRepositoryHelper.getCurrentUserOrganizationId().block());
        User saved = userRepository.save(user).block();
        instanceRoleService.setRole(saved, role).block();
        return saved;
    }

    private Datasource newDatasource() {
        DatasourceConfiguration configuration = new DatasourceConfiguration();
        configuration.setUrl("https://" + SECRET_HOST);
        configuration.setHeaders(List.of(new Property("Authorization", SECRET_TOKEN)));
        HashMap<String, DatasourceStorageDTO> storages = new HashMap<>();
        storages.put(environmentId, new DatasourceStorageDTO(null, environmentId, configuration));

        Datasource unsaved = new Datasource();
        unsaved.setName(unique("roles-datasource"));
        unsaved.setWorkspaceId(workspaceId);
        unsaved.setPluginId(plugin.getId());
        unsaved.setDatasourceStorages(storages);
        return unsaved;
    }

    private WebTestClient clientFor(InstanceRole role) {
        User user = users.get(role);
        return webTestClient
                .mutateWith(mockAuthentication(
                        new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities())))
                .mutateWith(csrf());
    }

    private static <T> Mono<T> runAs(Mono<T> publisher, User user) {
        return publisher.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}

package com.appsmith.server.projectsrepo;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionDTO;
import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.Property;
import com.appsmith.server.approvals.ApplicationApprovalService;
import com.appsmith.server.approvals.ApprovalFingerprint;
import com.appsmith.server.approvals.ApprovalStatus;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.MockPluginExecutor;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.PluginRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.LayoutActionService;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The projects repo's export and import of the edit copy, through the real services and permissions. */
@SpringBootTest
class ProjectsRepoServiceTest {

    @Autowired
    ProjectsRepoService projectsRepoService;

    @Autowired
    ApplicationApprovalService approvalService;

    @Autowired
    ApprovalFingerprint fingerprint;

    @Autowired
    ApplicationPageService applicationPageService;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    @Autowired
    Gson gson;

    @Autowired
    LayoutActionService layoutActionService;

    @Autowired
    NewActionRepository newActionRepository;

    @Autowired
    PluginRepository pluginRepository;

    // Plugin jars are not loaded in tests; creating an API query needs its executor.
    @MockitoBean
    PluginExecutorHelper pluginExecutorHelper;

    Map<InstanceRole, User> users;
    String workspaceId;
    Application application;

    @BeforeEach
    void setUp() {
        Mockito.when(pluginExecutorHelper.getPluginExecutor(Mockito.any()))
                .thenReturn(Mono.just(new MockPluginExecutor()));
        workspaceId = defaultWorkspaceService.getIds().block().workspaceId();
        users = new EnumMap<>(InstanceRole.class);
        for (InstanceRole role : InstanceRole.values()) {
            users.put(role, createUserWithRole(role));
        }
        Application unsaved = new Application();
        unsaved.setName("projects-repo-app-" + UUID.randomUUID());
        application = runAs(applicationPageService.createApplication(unsaved, workspaceId), InstanceRole.DEV);
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER);
    }

    @Test
    void should_exportEditCopyOnly_when_devExportsLiveApp() {
        // When
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));

        // Then
        assertThat(exported)
                .containsKeys("exportedApplication", "pageList")
                .doesNotContainKeys("publishedTheme", "decryptedFields", "datasourceConfigurationStructureList");
        Map<String, Object> exportedApplication = asMap(exported.get("exportedApplication"));
        assertThat(exportedApplication.get("name")).isEqualTo(application.getName());
        assertThat(exportedApplication)
                .doesNotContainKeys("publishedPages", "publishedApplicationDetail", "lastDeployedAt");
        assertThat((List<?>) exported.get("pageList")).isNotEmpty().allSatisfy(page -> assertThat(asMap(page))
                .containsKey("unpublishedPage")
                .doesNotContainKey("publishedPage"));
    }

    @Test
    void should_refuseExport_when_userCannotEditApp() {
        // When
        Optional<AppsmithError> error = runAs(
                projectsRepoService
                        .exportEditCopy(application.getId())
                        .map(exported -> Optional.<AppsmithError>empty())
                        .onErrorResume(
                                AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError()))),
                InstanceRole.USER);

        // Then
        assertThat(error).isPresent();
    }

    @Test
    void should_replaceEditCopyAndKeepAppLive_when_devImportsExportBack() {
        // Given
        String viewModeBefore = fingerprint.ofViewMode(application.getId()).block();
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));
        asMap(exported.get("exportedApplication")).put("unpublishedAppLayout", Map.of("type", "MOBILE"));

        // When
        ProjectsRepoService.ImportResult result = runAs(
                projectsRepoService.importEditCopy(workspaceId, application.getId(), gson.toJson(exported)),
                InstanceRole.DEV);

        // Then
        assertThat(result.applicationId()).isEqualTo(application.getId());
        assertThat(result.unchanged()).isFalse();
        Application stored = applicationRepository.findById(application.getId()).block();
        assertThat(stored.getLastDeployedAt()).isNotNull();
        assertThat(stored.getUnpublishedAppLayout().getType()).isEqualTo(Application.AppLayout.Type.MOBILE);
        assertThat(fingerprint.ofViewMode(application.getId()).block()).isEqualTo(viewModeBefore);
    }

    @Test
    void should_skipImportAndStayApproved_when_devImportsUnchangedExport() {
        // Given
        String editModeBefore = fingerprint.ofEditMode(application.getId()).block();
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));

        // When
        ProjectsRepoService.ImportResult result = runAs(
                projectsRepoService.importEditCopy(workspaceId, application.getId(), gson.toJson(exported)),
                InstanceRole.DEV);

        // Then
        assertThat(result.unchanged()).isTrue();
        assertThat(result.applicationId()).isEqualTo(application.getId());
        assertThat(fingerprint.ofEditMode(application.getId()).block()).isEqualTo(editModeBefore);
        assertThat(runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV)
                        .status())
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void should_createNewAppThatIsNotLive_when_devImportsWithoutApplicationId() {
        // Given
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));

        // When
        ProjectsRepoService.ImportResult result =
                runAs(projectsRepoService.importEditCopy(workspaceId, null, gson.toJson(exported)), InstanceRole.DEV);

        // Then
        assertThat(result.applicationId()).isNotEqualTo(application.getId());
        assertThat(applicationRepository
                        .findById(result.applicationId())
                        .block()
                        .getLastDeployedAt())
                .isNull();
    }

    @Test
    void should_exportStrippedSecretValues_when_apiQueryHasLiteralSecrets() {
        // Given
        runAs(layoutActionService.createSingleAction(apiQueryWithSecrets()), InstanceRole.DEV);

        // When
        String exported = gson.toJson(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));

        // Then
        assertThat(exported)
                .contains(EditCopyJson.STRIPPED_VALUE, "application/json", "{{Input1.text}}")
                .doesNotContain("Bearer s3cret", "k-123", "t-456", "session\\u003dabc", "session=abc");
    }

    @Test
    void should_keepStoredSecretValues_when_devPushesChangedPulledApp() {
        // Given
        ActionDTO query = runAs(layoutActionService.createSingleAction(apiQueryWithSecrets()), InstanceRole.DEV);
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));
        asMap(exported.get("exportedApplication")).put("unpublishedAppLayout", Map.of("type", "MOBILE"));

        // When
        ProjectsRepoService.ImportResult result = runAs(
                projectsRepoService.importEditCopy(workspaceId, application.getId(), gson.toJson(exported)),
                InstanceRole.DEV);

        // Then
        assertThat(result.unchanged()).isFalse();
        ActionDTO stored = newActionRepository.findById(query.getId()).block().getUnpublishedAction();
        assertThat(stored.getActionConfiguration().getHeaders())
                .extracting(Property::getKey, Property::getValue)
                .containsExactly(
                        tuple("Authorization", "Bearer s3cret"),
                        tuple("Content-Type", "application/json"),
                        tuple("X-Api-Key", "{{Input1.text}}"));
        assertThat(stored.getActionConfiguration().getQueryParameters())
                .extracting(Property::getKey, Property::getValue)
                .containsExactly(tuple("api_key", "k-123"), tuple("page", "1"));
        assertThat(stored.getActionConfiguration().getPath()).isEqualTo("/orders?token=t-456&page=2");
        assertThat(stored.getDatasource().getDatasourceConfiguration().getHeaders())
                .extracting(Property::getKey, Property::getValue)
                .containsExactly(tuple("Cookie", "session=abc"));
    }

    @Test
    void should_skipImport_when_devPushesUnchangedAppWithStrippedSecrets() {
        // Given
        runAs(layoutActionService.createSingleAction(apiQueryWithSecrets()), InstanceRole.DEV);
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));

        // When
        ProjectsRepoService.ImportResult result = runAs(
                projectsRepoService.importEditCopy(workspaceId, application.getId(), gson.toJson(exported)),
                InstanceRole.DEV);

        // Then
        assertThat(result.unchanged()).isTrue();
    }

    @Test
    void should_refuseNewApp_when_fileHasStrippedSecretValues() {
        // Given
        runAs(layoutActionService.createSingleAction(apiQueryWithSecrets()), InstanceRole.DEV);
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));

        // When
        Optional<AppsmithException> error = runAs(
                projectsRepoService
                        .importEditCopy(workspaceId, null, gson.toJson(exported))
                        .map(imported -> Optional.<AppsmithException>empty())
                        .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception))),
                InstanceRole.DEV);

        // Then
        assertThat(error).hasValueSatisfying(exception -> {
            assertThat(exception.getError()).isEqualTo(AppsmithError.GENERIC_BAD_REQUEST);
            assertThat(exception.getMessage()).contains("query 'SecretApi' headers 'Authorization'");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.net", "https://api.example.com:8443", "http://api.example.com"})
    void should_refusePushAndSaveNothing_when_pulledQueryWithStrippedSecretsPointsElsewhere(String url) {
        // Given
        ActionDTO query = runAs(layoutActionService.createSingleAction(apiQueryWithSecrets()), InstanceRole.DEV);
        String editModeBefore = fingerprint.ofEditMode(application.getId()).block();
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));
        asMap(asMap(asMap(editCopyOf(exported, "SecretApi").get("datasource")).get("datasourceConfiguration")))
                .put("url", url);

        // When
        Optional<AppsmithException> error = runAs(
                projectsRepoService
                        .importEditCopy(workspaceId, application.getId(), gson.toJson(exported))
                        .map(imported -> Optional.<AppsmithException>empty())
                        .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception))),
                InstanceRole.DEV);

        // Then
        assertThat(error).hasValueSatisfying(exception -> {
            assertThat(exception.getError()).isEqualTo(AppsmithError.GENERIC_BAD_REQUEST);
            assertThat(exception.getMessage())
                    .contains(
                            "another scheme, host, port or datasource",
                            "query 'SecretApi' headers 'Authorization'",
                            "query 'SecretApi' queryParameters 'api_key'",
                            "query 'SecretApi' headers 'Cookie'",
                            "query 'SecretApi' URL parameter 'token'")
                    .doesNotContain("Bearer s3cret", "k-123", "t-456", "session=abc");
        });
        ActionDTO stored = newActionRepository.findById(query.getId()).block().getUnpublishedAction();
        assertThat(stored.getDatasource().getDatasourceConfiguration().getUrl()).isEqualTo("https://api.example.com");
        assertThat(stored.getActionConfiguration().getHeaders())
                .extracting(Property::getKey, Property::getValue)
                .contains(tuple("Authorization", "Bearer s3cret"));
        assertThat(fingerprint.ofEditMode(application.getId()).block()).isEqualTo(editModeBefore);
    }

    @Test
    void should_keepStoredSecretValues_when_devPushesPulledQueryWithChangedPathOnSameHost() {
        // Given
        ActionDTO query = runAs(layoutActionService.createSingleAction(apiQueryWithSecrets()), InstanceRole.DEV);
        Map<String, Object> exported =
                asMap(runAs(projectsRepoService.exportEditCopy(application.getId()), InstanceRole.DEV));
        Map<String, Object> actionConfiguration =
                asMap(editCopyOf(exported, "SecretApi").get("actionConfiguration"));
        actionConfiguration.put("path", ((String) actionConfiguration.get("path")).replace("/orders?", "/orders/all?"));

        // When
        ProjectsRepoService.ImportResult result = runAs(
                projectsRepoService.importEditCopy(workspaceId, application.getId(), gson.toJson(exported)),
                InstanceRole.DEV);

        // Then
        assertThat(result.unchanged()).isFalse();
        ActionDTO stored = newActionRepository.findById(query.getId()).block().getUnpublishedAction();
        assertThat(stored.getActionConfiguration().getPath()).isEqualTo("/orders/all?token=t-456&page=2");
        assertThat(stored.getActionConfiguration().getHeaders())
                .extracting(Property::getKey, Property::getValue)
                .contains(tuple("Authorization", "Bearer s3cret"));
        assertThat(stored.getDatasource().getDatasourceConfiguration().getHeaders())
                .extracting(Property::getKey, Property::getValue)
                .containsExactly(tuple("Cookie", "session=abc"));
    }

    /** The edit copy (action DTO) of the query {@code name} in an exported app JSON tree. */
    private static Map<String, Object> editCopyOf(Map<String, Object> exported, String name) {
        return ((List<?>) exported.get("actionList"))
                .stream()
                        .map(action -> asMap(asMap(action).get("unpublishedAction")))
                        .filter(dto -> name.equals(dto.get("name")))
                        .findFirst()
                        .orElseThrow();
    }

    /** An API query with its own URL (no saved datasource) and literal secrets in its headers, parameters and URLs. */
    private ActionDTO apiQueryWithSecrets() {
        ActionDTO action = new ActionDTO();
        action.setName("SecretApi");
        action.setPageId(application.getPages().get(0).getId());
        Datasource datasource = new Datasource();
        datasource.setName("DEFAULT_REST_DATASOURCE");
        datasource.setWorkspaceId(workspaceId);
        datasource.setPluginId(
                pluginRepository.findByPackageName("restapi-plugin").block().getId());
        DatasourceConfiguration datasourceConfiguration = new DatasourceConfiguration();
        datasourceConfiguration.setUrl("https://api.example.com");
        datasourceConfiguration.setHeaders(List.of(new Property("Cookie", "session=abc")));
        datasource.setDatasourceConfiguration(datasourceConfiguration);
        action.setDatasource(datasource);
        ActionConfiguration actionConfiguration = new ActionConfiguration();
        actionConfiguration.setHttpMethod(HttpMethod.GET);
        actionConfiguration.setPath("/orders?token=t-456&page=2");
        actionConfiguration.setHeaders(List.of(
                new Property("Authorization", "Bearer s3cret"),
                new Property("Content-Type", "application/json"),
                new Property("X-Api-Key", "{{Input1.text}}")));
        actionConfiguration.setQueryParameters(List.of(new Property("api_key", "k-123"), new Property("page", "1")));
        action.setActionConfiguration(actionConfiguration);
        return action;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object tree) {
        return (Map<String, Object>) tree;
    }

    private <T> T runAs(Mono<T> publisher, InstanceRole role) {
        User user = users.get(role);
        return publisher
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl(
                        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))))
                .block();
    }

    private User createUserWithRole(InstanceRole role) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@projects-repo.test");
        user.setName(role.name());
        user.setIsEnabled(true);
        user.setOrganizationId(
                cacheableRepositoryHelper.getCurrentUserOrganizationId().block());
        User saved = userRepository.save(user).block();
        instanceRoleService.setRole(saved, role).block();
        return saved;
    }
}

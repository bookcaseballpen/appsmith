package com.appsmith.server.projectsrepo;

import com.appsmith.server.approvals.ApplicationApprovalService;
import com.appsmith.server.approvals.ApprovalFingerprint;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ApplicationPageService;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    Map<InstanceRole, User> users;
    String workspaceId;
    Application application;

    @BeforeEach
    void setUp() {
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
        Application stored = applicationRepository.findById(application.getId()).block();
        assertThat(stored.getLastDeployedAt()).isNotNull();
        assertThat(stored.getUnpublishedAppLayout().getType()).isEqualTo(Application.AppLayout.Type.MOBILE);
        assertThat(fingerprint.ofViewMode(application.getId()).block()).isEqualTo(viewModeBefore);
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

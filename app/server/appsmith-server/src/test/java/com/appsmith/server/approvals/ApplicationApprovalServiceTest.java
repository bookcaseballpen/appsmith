package com.appsmith.server.approvals;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.authentication.tokens.McpTokenAuthentication;
import com.appsmith.server.constants.ArtifactType;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationApproval;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.PageDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.exports.internal.ExportService;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.repositories.ApplicationApprovalRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ApplicationPageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The release approval state machine and who may move it, through the real services and, for the approver filter,
 * the real security chain.
 */
@SpringBootTest
@DirtiesContext
class ApplicationApprovalServiceTest {

    @Autowired
    ReactiveWebApplicationContext context;

    @Autowired
    ApplicationApprovalService approvalService;

    @Autowired
    ApprovalFingerprint fingerprint;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    @Autowired
    ApplicationRepository applicationRepository;

    @MockitoSpyBean
    ApplicationPageService applicationPageService;

    @Autowired
    ApplicationService applicationService;

    @Autowired
    ApplicationApprovalRepository approvalRepository;

    @Autowired
    ExportService exportService;

    @Autowired
    ImportService importService;

    WebTestClient webTestClient;
    Map<InstanceRole, User> users;
    String workspaceId;
    Application application;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();
        workspaceId = defaultWorkspaceService.getIds().block().workspaceId();
        users = new EnumMap<>(InstanceRole.class);
        for (InstanceRole role : InstanceRole.values()) {
            users.put(role, createUserWithRole(role));
        }
        Application unsaved = new Application();
        unsaved.setName("approval-app-" + UUID.randomUUID());
        application = runAs(applicationPageService.createApplication(unsaved, workspaceId), users.get(InstanceRole.DEV))
                .block();
    }

    @Test
    void should_beDraft_when_appWasNeverSubmitted() {
        // When
        ApplicationApprovalDTO status = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);

        // Then
        assertThat(status.status()).isEqualTo(ApprovalStatus.DRAFT);
        assertThat(status.history()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_submitAsEveryRoleButUser_when_roleSubmits(InstanceRole role) {
        // When
        Optional<AppsmithError> error = errorOf(approvalService.submit(application.getId()), role);

        // Then
        if (role != InstanceRole.USER) {
            assertThat(error).isEmpty();
            assertThat(runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV)
                            .status())
                    .isEqualTo(ApprovalStatus.SUBMITTED);
        } else {
            assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
        }
    }

    @Test
    void should_listRequestForApprovers_when_devSubmits() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        List<ApplicationApprovalDTO> pending = runAs(approvalService.listPending(), InstanceRole.APPROVER);

        // Then
        assertThat(pending)
                .filteredOn(dto -> dto.applicationId().equals(application.getId()))
                .singleElement()
                .satisfies(dto -> {
                    assertThat(dto.submittedBy())
                            .isEqualTo(users.get(InstanceRole.DEV).getEmail());
                    assertThat(dto.defaultBasePageId()).isNotBlank();
                    assertThat(dto.version()).hasSize(12);
                });
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"DEV", "USER"})
    void should_refusePendingList_when_callerCannotReview(InstanceRole role) {
        // When
        Optional<AppsmithError> error = errorOf(approvalService.listPending(), role);

        // Then
        assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
    }

    @ParameterizedTest
    @EnumSource(InstanceRole.class)
    void should_approveOnlyAsApproverOrAdmin_when_roleApproves(InstanceRole role) {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        Optional<AppsmithError> error = errorOf(approvalService.approve(application.getId()), role);

        // Then
        Application stored = applicationRepository.findById(application.getId()).block();
        boolean reviewer = role == InstanceRole.APPROVER || role == InstanceRole.ADMIN;
        if (reviewer) {
            assertThat(error).isEmpty();
            assertThat(stored.getLastDeployedAt()).isNotNull();
        } else {
            assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
            assertThat(stored.getLastDeployedAt()).isNull();
        }
    }

    @Test
    void should_publishExactlySubmittedVersionAndShowItToUsers_when_approverApproves() {
        // Given
        String submitted = runAs(approvalService.submit(application.getId()), InstanceRole.DEV)
                .version();

        // When
        ApplicationApprovalDTO approved = runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // Then
        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.reviewedBy())
                .isEqualTo(users.get(InstanceRole.APPROVER).getEmail());
        assertThat(fingerprint.ofViewMode(application.getId()).block()).startsWith(submitted);
        assertThat(fingerprint.ofEditMode(application.getId()).block()).startsWith(submitted);
        List<String> visibleToUser = runAs(
                applicationService
                        .findByWorkspaceId(workspaceId, AclPermission.READ_APPLICATIONS)
                        .map(Application::getId)
                        .collectList(),
                InstanceRole.USER);
        assertThat(visibleToUser).contains(application.getId());
        assertThat(approved.history())
                .extracting(ApprovalEvent::getType)
                .containsExactly(ApprovalEvent.Type.SUBMITTED, ApprovalEvent.Type.APPROVED);
    }

    @Test
    void should_cancelRequest_when_devChangesAppAfterSubmitting() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        addPage(InstanceRole.DEV);

        // Then
        ApplicationApprovalDTO status = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);
        assertThat(status.status()).isEqualTo(ApprovalStatus.DRAFT);
        assertThat(status.history()).last().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(ApprovalEvent.Type.CANCELLED);
            assertThat(event.getComment()).isEqualTo(ApplicationApprovalService.CHANGED_AFTER_SUBMIT);
        });
        assertThat(runAs(approvalService.listPending(), InstanceRole.APPROVER))
                .noneMatch(dto -> dto.applicationId().equals(application.getId()));
    }

    @Test
    void should_refuseApproveAndStayOffline_when_appChangedAfterSubmitting() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        addPage(InstanceRole.DEV);

        // When
        Optional<AppsmithError> error = errorOf(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // Then
        assertThat(error).contains(AppsmithError.GENERIC_BAD_REQUEST);
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNull();
    }

    @Test
    void should_becomeDraft_when_approvedAppChangesLater() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // When
        addPage(InstanceRole.DEV);

        // Then
        assertThat(runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV)
                        .status())
                .isEqualTo(ApprovalStatus.DRAFT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void should_requireComment_when_approverRejects(String comment) {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        Optional<AppsmithError> error =
                errorOf(approvalService.reject(application.getId(), comment), InstanceRole.APPROVER);

        // Then
        assertThat(error).contains(AppsmithError.INVALID_PARAMETER);
        assertThat(runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV)
                        .status())
                .isEqualTo(ApprovalStatus.SUBMITTED);
    }

    @Test
    void should_showCommentToDevAndAllowResubmit_when_approverRejects() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        runAs(approvalService.reject(application.getId(), " Fix the title "), InstanceRole.APPROVER);
        ApplicationApprovalDTO rejected = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);
        ApplicationApprovalDTO resubmitted = runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // Then
        assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.comment()).isEqualTo("Fix the title");
        assertThat(resubmitted.status()).isEqualTo(ApprovalStatus.SUBMITTED);
        assertThat(resubmitted.comment()).isNull();
        assertThat(resubmitted.history())
                .extracting(ApprovalEvent::getType)
                .containsExactly(
                        ApprovalEvent.Type.SUBMITTED, ApprovalEvent.Type.REJECTED, ApprovalEvent.Type.SUBMITTED);
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"DEV", "USER"})
    void should_refuseReject_when_callerCannotReview(InstanceRole role) {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        Optional<AppsmithError> error = errorOf(approvalService.reject(application.getId(), "No"), role);

        // Then
        assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
    }

    @Test
    void should_refuseSecondSubmit_when_requestIsAlreadyWaiting() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When
        Optional<AppsmithError> error = errorOf(approvalService.submit(application.getId()), InstanceRole.DEV);

        // Then
        assertThat(error).contains(AppsmithError.GENERIC_BAD_REQUEST);
    }

    @Test
    void should_refuseApprove_when_adminCallsThroughMcpToken() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        User admin = users.get(InstanceRole.ADMIN);
        UsernamePasswordAuthenticationToken mcpAuthentication = new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority(McpTokenAuthentication.MCP_AUTHORITY)));

        // When
        Optional<AppsmithError> error = approvalService
                .approve(application.getId())
                .map(dto -> Optional.<AppsmithError>empty())
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError())))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(mcpAuthentication))))
                .block();

        // Then
        assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
    }

    @Test
    void should_letApproverThroughReadOnlyFilter_when_approvingAndRejectingOverHttp() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);

        // When / Then
        clientFor(InstanceRole.APPROVER)
                .post()
                .uri("/api/v1/approvals/applications/{id}/reject", application.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("comment", "Needs a logo"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.status")
                .isEqualTo("REJECTED");
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        clientFor(InstanceRole.APPROVER)
                .post()
                .uri("/api/v1/approvals/applications/{id}/approve", application.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.status")
                .isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "APPROVER"})
    void should_approveOwnSubmitOverHttp_when_reviewerSubmitsAndApproves(InstanceRole role) {
        // When / Then
        clientFor(role)
                .post()
                .uri("/api/v1/approvals/applications/{id}/submit", application.getId())
                .exchange()
                .expectStatus()
                .isOk();
        clientFor(role)
                .post()
                .uri("/api/v1/approvals/applications/{id}/approve", application.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.status")
                .isEqualTo("APPROVED");
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNotNull();
    }

    @Test
    void should_refuseSubmit_when_callerIsUser() {
        // When / Then
        clientFor(InstanceRole.USER)
                .post()
                .uri("/api/v1/approvals/applications/{id}/submit", application.getId())
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }

    @Test
    void should_failSecondReviewersReject_when_firstReviewerIsApproving() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        List<Optional<AppsmithError>> rejectDuringPublish = new ArrayList<>();
        duringPublish(() -> runAs(
                        errorOfAsync(approvalService.reject(application.getId(), "Not yet")),
                        users.get(InstanceRole.ADMIN))
                .doOnNext(rejectDuringPublish::add)
                .then());

        // When
        ApplicationApprovalDTO approved = runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // Then
        assertThat(rejectDuringPublish).containsExactly(Optional.of(AppsmithError.GENERIC_BAD_REQUEST));
        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.history())
                .extracting(ApprovalEvent::getType)
                .containsExactly(ApprovalEvent.Type.SUBMITTED, ApprovalEvent.Type.APPROVED);
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNotNull();
    }

    @Test
    void should_refuseApproveAndStayOffline_when_otherReviewerRejectedFirst() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        runAs(approvalService.reject(application.getId(), "Not yet"), InstanceRole.ADMIN);

        // When
        Optional<AppsmithError> error = errorOf(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // Then
        assertThat(error).contains(AppsmithError.GENERIC_BAD_REQUEST);
        ApplicationApprovalDTO status = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);
        assertThat(status.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(status.comment()).isEqualTo("Not yet");
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNull();
    }

    @Test
    void should_holdClaimWhilePublishing_when_approverApproves() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        List<ApprovalStatus> statusDuringPublish = new ArrayList<>();
        List<Optional<AppsmithError>> resubmitDuringPublish = new ArrayList<>();
        duringPublish(() -> runAs(approvalService.getStatus(application.getId()), users.get(InstanceRole.DEV))
                .doOnNext(dto -> statusDuringPublish.add(dto.status()))
                .then(runAs(errorOfAsync(approvalService.submit(application.getId())), users.get(InstanceRole.DEV)))
                .doOnNext(resubmitDuringPublish::add)
                .then());

        // When
        runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // Then
        assertThat(statusDuringPublish).containsExactly(ApprovalStatus.APPROVING);
        assertThat(resubmitDuringPublish).containsExactly(Optional.of(AppsmithError.GENERIC_BAD_REQUEST));
    }

    @Test
    void should_returnToSubmittedAndAllowRetry_when_publishFails() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        doReturn(Mono.error(new IllegalStateException("publish failed")))
                .when(applicationPageService)
                .publish(application.getId(), true);

        // When
        Throwable failure = runAs(
                approvalService.approve(application.getId()).then(Mono.<Throwable>empty()).onErrorResume(Mono::just),
                InstanceRole.APPROVER);

        // Then
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("publish failed");
        ApplicationApprovalDTO status = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);
        assertThat(status.status()).isEqualTo(ApprovalStatus.SUBMITTED);
        assertThat(status.reviewedBy()).isNull();
        assertThat(status.history()).extracting(ApprovalEvent::getType).containsExactly(ApprovalEvent.Type.SUBMITTED);
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNull();
        Mockito.reset(applicationPageService);
        assertThat(runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER)
                        .status())
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void should_takeAppOfflineAndCancel_when_appChangesWhilePublishing() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        duringPublish(() -> pageCreation(InstanceRole.DEV));

        // When
        Optional<AppsmithError> error = errorOf(approvalService.approve(application.getId()), InstanceRole.APPROVER);

        // Then
        assertThat(error).contains(AppsmithError.GENERIC_BAD_REQUEST);
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNull();
        ApplicationApprovalDTO status = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);
        assertThat(status.status()).isEqualTo(ApprovalStatus.DRAFT);
        assertThat(status.history()).last().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(ApprovalEvent.Type.CANCELLED);
            assertThat(event.getComment()).isEqualTo(ApplicationApprovalService.CHANGED_WHILE_PUBLISHING);
        });
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "APPROVER", "DEV"})
    void should_refuseDirectPublish_when_roleCallsPublishEndpoint(InstanceRole role) {
        // When / Then
        clientFor(role)
                .post()
                .uri("/api/v1/applications/publish/{id}", application.getId())
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(errorOf(applicationPageService.publish(application.getId(), true), role))
                .contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNull();
    }

    @Test
    void should_takeLiveAppOffline_when_adminImportsOverIt() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER);
        ApplicationJson export = runAs(
                exportService
                        .exportByArtifactIdAndBranchName(application.getId(), "", ArtifactType.APPLICATION)
                        .map(json -> (ApplicationJson) json),
                InstanceRole.ADMIN);

        // When
        runAs(
                importService.updateNonGitConnectedArtifactFromJson(workspaceId, application.getId(), export),
                InstanceRole.ADMIN);

        // Then
        assertThat(applicationRepository.findById(application.getId()).block().getLastDeployedAt())
                .isNull();
    }

    @Test
    void should_releaseClaimBackToSubmitted_when_claimIsStale() {
        // Given
        runAs(approvalService.submit(application.getId()), InstanceRole.DEV);
        ApplicationApproval stored = approvalRepository.findById(application.getId()).block();
        stored.setStatus(ApprovalStatus.APPROVING);
        stored.setReviewedAt(Instant.now().minus(ApplicationApprovalService.STALE_CLAIM).minusSeconds(1));
        approvalRepository.save(stored).block();

        // When
        ApplicationApprovalDTO status = runAs(approvalService.getStatus(application.getId()), InstanceRole.DEV);

        // Then
        assertThat(status.status()).isEqualTo(ApprovalStatus.SUBMITTED);
        assertThat(runAs(approvalService.approve(application.getId()), InstanceRole.APPROVER)
                        .status())
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void should_giveSameFingerprint_when_unchangedAppIsReadTwice() {
        // When
        String first = fingerprint.ofEditMode(application.getId()).block();
        String second = fingerprint.ofEditMode(application.getId()).block();

        // Then
        assertThat(first).hasSize(64).isEqualTo(second);
    }

    private void addPage(InstanceRole role) {
        pageCreation(role).block();
    }

    private Mono<Void> pageCreation(InstanceRole role) {
        PageDTO page = new PageDTO();
        page.setName("Page" + UUID.randomUUID().toString().substring(0, 8));
        page.setApplicationId(application.getId());
        return runAs(applicationPageService.createPage(page), users.get(role)).then();
    }

    private <T> Optional<AppsmithError> errorOf(Mono<T> call, InstanceRole role) {
        return runAs(errorOfAsync(call), role);
    }

    private static <T> Mono<Optional<AppsmithError>> errorOfAsync(Mono<T> call) {
        return call.map(result -> Optional.<AppsmithError>empty())
                .onErrorResume(AppsmithException.class, exception -> Mono.just(Optional.of(exception.getError())));
    }

    /** Runs {@code sideEffect} inside the approved release's publish, before the real publish copies the app. */
    private void duringPublish(Supplier<Mono<Void>> sideEffect) {
        doAnswer(invocation -> sideEffect.get().then(Mono.defer(() -> {
                    try {
                        return (Mono<?>) invocation.callRealMethod();
                    } catch (Throwable error) {
                        return Mono.error(error);
                    }
                })))
                .when(applicationPageService)
                .publish(application.getId(), true);
    }

    private <T> T runAs(Mono<T> publisher, InstanceRole role) {
        return runAs(publisher, users.get(role)).block();
    }

    private static <T> Mono<T> runAs(Mono<T> publisher, User user) {
        return publisher.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))));
    }

    private WebTestClient clientFor(InstanceRole role) {
        User user = users.get(role);
        return webTestClient
                .mutateWith(mockAuthentication(
                        new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities())))
                .mutateWith(csrf());
    }

    private User createUserWithRole(InstanceRole role) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@approvals.test");
        user.setName(role.name());
        user.setIsEnabled(true);
        user.setOrganizationId(
                cacheableRepositoryHelper.getCurrentUserOrganizationId().block());
        User saved = userRepository.save(user).block();
        instanceRoleService.setRole(saved, role).block();
        return saved;
    }
}

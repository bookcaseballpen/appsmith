package com.appsmith.server.approvals;

import com.appsmith.server.authentication.tokens.McpTokenAuthentication;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationApproval;
import com.appsmith.server.domains.ApplicationPage;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.repositories.ApplicationApprovalRepository;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.roles.PublishApproval;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.solutions.ApplicationPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fork: the release approval flow, the only way to publish an app. A DEV, ADMIN or APPROVER submits the app's current
 * edit-mode version; an APPROVER or ADMIN (possibly the one who submitted) approves it, which publishes it, or rejects
 * it with a comment.
 *
 * <p>The submitted version is frozen as an {@link ApprovalFingerprint} of the edit-mode content. The request is
 * cancelled as soon as the content no longer matches: every read of the state (the editor's status chip, the approvers'
 * list, approve and reject) compares the stored fingerprint with the app as it is now. That needs no hook in the many
 * paths that change an app (editor, import, git, MCP, AI), so none can slip past it.
 *
 * <p>Approve publishes only the submitted version: it refuses when the edit-mode content has changed, and after
 * publishing it checks that the view-mode copy has the submitted fingerprint. If a change landed while the publish ran,
 * the app is taken out of users' sight ({@link PublishApproval#markNotLive}) and the request is cancelled, so users
 * never see an unapproved version.
 *
 * <p>Approve and reject are for people only: they are refused for MCP tokens here, besides the MCP allowlist not
 * listing them.
 */
@Slf4j
@Service
public class ApplicationApprovalService {

    static final int HISTORY_LIMIT = 20;

    static final String CHANGED_AFTER_SUBMIT = "The app changed after it was submitted. Submit it again.";

    static final String CHANGED_WHILE_PUBLISHING =
            "The app changed while it was being published, so it was taken offline. Submit it again.";

    static final Duration STALE_CLAIM = Duration.ofMinutes(10);

    private static final int VERSION_LENGTH = 12;

    // The longest note a submitter may leave for the approver (it is kept in the history).
    static final int MAX_NOTE_LENGTH = 500;

    private final ApplicationApprovalRepository repository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationPermission applicationPermission;
    private final ApplicationPageService applicationPageService;
    private final ApprovalFingerprint fingerprint;
    private final InstanceRoleService instanceRoleService;
    private final SessionUserService sessionUserService;
    private final PublishApproval publishApproval;

    public ApplicationApprovalService(
            ApplicationApprovalRepository repository,
            ApplicationRepository applicationRepository,
            ApplicationPermission applicationPermission,
            ApplicationPageService applicationPageService,
            ApprovalFingerprint fingerprint,
            InstanceRoleService instanceRoleService,
            SessionUserService sessionUserService,
            PublishApproval publishApproval) {
        this.repository = repository;
        this.applicationRepository = applicationRepository;
        this.applicationPermission = applicationPermission;
        this.applicationPageService = applicationPageService;
        this.fingerprint = fingerprint;
        this.instanceRoleService = instanceRoleService;
        this.sessionUserService = sessionUserService;
        this.publishApproval = publishApproval;
    }

    /** The state for anyone who can open the app in the editor (DEV, APPROVER, ADMIN). */
    public Mono<ApplicationApprovalDTO> getStatus(String applicationId) {
        return findApplication(applicationId, false)
                .flatMap(application -> currentState(application).map(state -> toDTO(application, state)));
    }

    public Mono<ApplicationApprovalDTO> submit(String applicationId) {
        return submit(applicationId, null);
    }

    /** Submits with an optional note for the approver, kept as the comment of the SUBMITTED history event. */
    public Mono<ApplicationApprovalDTO> submit(String applicationId, String note) {
        String trimmedNote = StringUtils.hasText(note) ? note.trim() : null;
        if (trimmedNote != null && trimmedNote.length() > MAX_NOTE_LENGTH) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "note"));
        }
        return instanceRoleService
                .requireCurrentUserRole(
                        "Submit for approval", InstanceRole.ADMIN, InstanceRole.DEV, InstanceRole.APPROVER)
                .then(findApplication(applicationId, false))
                .flatMap(application -> Mono.zip(
                                ensureDocument(application).flatMap(this::checkUnchanged),
                                fingerprint.ofEditMode(application.getId()),
                                currentUserEmail())
                        .flatMap(tuple -> {
                            ApplicationApproval state = tuple.getT1();
                            String submittedFingerprint = tuple.getT2();
                            if (state.getStatus() == ApprovalStatus.SUBMITTED) {
                                return badRequest("This version is already waiting for approval.");
                            }
                            if (state.getStatus() == ApprovalStatus.APPROVING) {
                                return badRequest("This version is being approved and published right now.");
                            }
                            if (state.getStatus() == ApprovalStatus.APPROVED) {
                                return badRequest("This version is already approved and published.");
                            }
                            Instant now = Instant.now();
                            BridgeUpdate update = Bridge.update()
                                    .set(ApplicationApproval.Fields.status, ApprovalStatus.SUBMITTED)
                                    .set(ApplicationApproval.Fields.fingerprint, submittedFingerprint)
                                    .set(ApplicationApproval.Fields.submittedBy, tuple.getT3())
                                    .set(ApplicationApproval.Fields.submittedAt, now)
                                    .set(ApplicationApproval.Fields.reviewedBy, null)
                                    .set(ApplicationApproval.Fields.reviewedAt, null)
                                    .set(ApplicationApproval.Fields.comment, null);
                            return transition(
                                    state,
                                    update,
                                    new ApprovalEvent(ApprovalEvent.Type.SUBMITTED, tuple.getT3(), now, trimmedNote));
                        })
                        .map(state -> toDTO(application, state)));
    }

    /**
     * Claims the request (SUBMITTED to APPROVING, bumping its revision) before publishing, so a concurrent reject or
     * resubmit fails on the revision check instead of overwriting a release that is going live. A failed publish puts
     * the request back to SUBMITTED; a change that landed while publishing takes the app offline and cancels it.
     */
    public Mono<ApplicationApprovalDTO> approve(String applicationId) {
        return requireReviewer("Approve a release")
                .then(findApplication(applicationId, true))
                .flatMap(application -> Mono.zip(findPending(application), currentUserEmail())
                        .flatMap(tuple -> claim(tuple.getT1(), tuple.getT2()))
                        .flatMap(claimed -> publishClaimed(application, claimed))
                        .map(state -> toDTO(application, state)));
    }

    private Mono<ApplicationApproval> claim(ApplicationApproval state, String reviewer) {
        BridgeUpdate update = Bridge.update()
                .set(ApplicationApproval.Fields.status, ApprovalStatus.APPROVING)
                .set(ApplicationApproval.Fields.reviewedBy, reviewer)
                .set(ApplicationApproval.Fields.reviewedAt, Instant.now());
        return transition(state, update, null);
    }

    private Mono<ApplicationApproval> publishClaimed(Application application, ApplicationApproval claimed) {
        String approved = claimed.getFingerprint();
        return PublishApproval.asApprovedRelease(applicationPageService.publish(application.getId(), true))
                .onErrorResume(error -> {
                    log.warn("Publishing the approved release of application {} failed", application.getId(), error);
                    return transition(
                                    claimed,
                                    Bridge.update()
                                            .set(ApplicationApproval.Fields.status, ApprovalStatus.SUBMITTED)
                                            .set(ApplicationApproval.Fields.reviewedBy, null)
                                            .set(ApplicationApproval.Fields.reviewedAt, null),
                                    null)
                            .then(Mono.<Application>error(error));
                })
                .then(fingerprint.ofViewMode(application.getId()))
                .flatMap(published -> {
                    Instant now = Instant.now();
                    if (!approved.equals(published)) {
                        log.warn(
                                "Application {} changed while its approved version was published; taking it offline",
                                application.getId());
                        return publishApproval
                                .markNotLive(application.getId())
                                .then(transition(
                                        claimed,
                                        Bridge.update().set(ApplicationApproval.Fields.status, ApprovalStatus.DRAFT),
                                        new ApprovalEvent(
                                                ApprovalEvent.Type.CANCELLED, null, now, CHANGED_WHILE_PUBLISHING)))
                                .then(badRequest(CHANGED_WHILE_PUBLISHING));
                    }
                    BridgeUpdate update = Bridge.update()
                            .set(ApplicationApproval.Fields.status, ApprovalStatus.APPROVED)
                            .set(ApplicationApproval.Fields.reviewedAt, now)
                            .set(ApplicationApproval.Fields.comment, null);
                    return transition(
                            claimed,
                            update,
                            new ApprovalEvent(ApprovalEvent.Type.APPROVED, claimed.getReviewedBy(), now, null));
                });
    }

    public Mono<ApplicationApprovalDTO> reject(String applicationId, String comment) {
        if (!StringUtils.hasText(comment)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "comment"));
        }
        String trimmed = comment.trim();
        return requireReviewer("Reject a release")
                .then(findApplication(applicationId, true))
                .flatMap(application -> findPending(application)
                        .zipWith(currentUserEmail())
                        .flatMap(tuple -> {
                            Instant now = Instant.now();
                            BridgeUpdate update = Bridge.update()
                                    .set(ApplicationApproval.Fields.status, ApprovalStatus.REJECTED)
                                    .set(ApplicationApproval.Fields.reviewedBy, tuple.getT2())
                                    .set(ApplicationApproval.Fields.reviewedAt, now)
                                    .set(ApplicationApproval.Fields.comment, trimmed);
                            return transition(
                                    tuple.getT1(),
                                    update,
                                    new ApprovalEvent(ApprovalEvent.Type.REJECTED, tuple.getT2(), now, trimmed));
                        })
                        .map(state -> toDTO(application, state)));
    }

    /** Requests waiting for approval, oldest first. Requests whose app has changed are cancelled on the way. */
    public Mono<List<ApplicationApprovalDTO>> listPending() {
        return instanceRoleService
                .requireCurrentUserRole("Review releases", InstanceRole.ADMIN, InstanceRole.APPROVER)
                .thenMany(repository.findAllByStatus(ApprovalStatus.SUBMITTED))
                .concatMap(stored -> applicationRepository
                        .findById(stored.getApplicationId(), applicationPermission.getPublishPermission())
                        .flatMap(application -> checkUnchanged(stored)
                                .filter(state -> state.getStatus() == ApprovalStatus.SUBMITTED)
                                .map(state -> toDTO(application, state))))
                .sort(Comparator.comparing(
                        ApplicationApprovalDTO::submittedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collectList();
    }

    private Mono<Application> findApplication(String applicationId, boolean forReview) {
        return applicationRepository
                .findById(
                        applicationId,
                        forReview
                                ? applicationPermission.getPublishPermission()
                                : applicationPermission.getEditPermission())
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION, applicationId)));
    }

    private Mono<ApplicationApproval> findPending(Application application) {
        return currentState(application)
                .flatMap(state -> state.getStatus() == ApprovalStatus.SUBMITTED
                        ? Mono.just(state)
                        : badRequest(
                                "No version of this app is waiting for approval. It may have been cancelled because"
                                        + " the app changed after it was submitted."));
    }

    private Mono<Void> requireReviewer(String action) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(McpTokenAuthentication::isMcpPrincipal)
                .defaultIfEmpty(false)
                .flatMap(isMcp -> isMcp
                        ? Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, action + " via MCP"))
                        : instanceRoleService.requireCurrentUserRole(
                                action, InstanceRole.ADMIN, InstanceRole.APPROVER));
    }

    /** The stored state, checked against the app as it is now; DRAFT when the app never had a request. */
    private Mono<ApplicationApproval> currentState(Application application) {
        return repository
                .findById(application.getId())
                .flatMap(this::checkUnchanged)
                .defaultIfEmpty(newDocument(application));
    }

    /**
     * A submitted or approved version that no longer matches the app ends here: a submitted request is cancelled (and
     * the history says why), an approved one becomes a draft again. Rejected requests keep their comment until the
     * next submit.
     */
    private Mono<ApplicationApproval> checkUnchanged(ApplicationApproval state) {
        if (state.getStatus() == ApprovalStatus.APPROVING) {
            return releaseStaleClaim(state);
        }
        if (state.getStatus() != ApprovalStatus.SUBMITTED && state.getStatus() != ApprovalStatus.APPROVED) {
            return Mono.just(state);
        }
        return fingerprint
                .ofEditMode(state.getApplicationId())
                .defaultIfEmpty("")
                .flatMap(current -> {
                    if (current.equals(state.getFingerprint())) {
                        return Mono.just(state);
                    }
                    BridgeUpdate update = Bridge.update().set(ApplicationApproval.Fields.status, ApprovalStatus.DRAFT);
                    ApprovalEvent event = state.getStatus() == ApprovalStatus.SUBMITTED
                            ? new ApprovalEvent(ApprovalEvent.Type.CANCELLED, null, Instant.now(), CHANGED_AFTER_SUBMIT)
                            : null;
                    // Lost the race to another change: whatever that one stored is current.
                    return transition(state, update, event)
                            .onErrorResume(AppsmithException.class, error -> repository.findById(state.getId()));
                });
    }

    /**
     * A claim is held only while a publish runs; one older than {@link #STALE_CLAIM} was left by a server that stopped
     * mid-publish, so the request goes back to SUBMITTED (and is checked like any other).
     */
    private Mono<ApplicationApproval> releaseStaleClaim(ApplicationApproval state) {
        Instant claimedAt = state.getReviewedAt();
        if (claimedAt != null && claimedAt.isAfter(Instant.now().minus(STALE_CLAIM))) {
            return Mono.just(state);
        }
        BridgeUpdate update = Bridge.update()
                .set(ApplicationApproval.Fields.status, ApprovalStatus.SUBMITTED)
                .set(ApplicationApproval.Fields.reviewedBy, null)
                .set(ApplicationApproval.Fields.reviewedAt, null);
        return transition(state, update, null)
                .flatMap(this::checkUnchanged)
                .onErrorResume(AppsmithException.class, error -> repository.findById(state.getId()));
    }

    private Mono<ApplicationApproval> ensureDocument(Application application) {
        return repository.findById(application.getId()).switchIfEmpty(Mono.defer(() -> {
            ApplicationApproval created = newDocument(application);
            return repository.save(created);
        }));
    }

    private static ApplicationApproval newDocument(Application application) {
        ApplicationApproval state = new ApplicationApproval();
        state.setId(application.getId());
        state.setApplicationId(application.getId());
        state.setWorkspaceId(application.getWorkspaceId());
        state.setStatus(ApprovalStatus.DRAFT);
        state.setRevision(0);
        return state;
    }

    /**
     * Stores the update and the history event if nobody changed the request since {@code state} was read, and answers
     * the new state. Fails when someone did, so two reviewers (or a reviewer and a resubmit) cannot both win.
     */
    private Mono<ApplicationApproval> transition(ApplicationApproval state, BridgeUpdate update, ApprovalEvent event) {
        int revision = Objects.requireNonNullElse(state.getRevision(), 0);
        List<ApprovalEvent> history = new ArrayList<>(Objects.requireNonNullElse(state.getHistory(), List.of()));
        if (event != null) {
            history.add(event);
        }
        if (history.size() > HISTORY_LIMIT) {
            history = new ArrayList<>(history.subList(history.size() - HISTORY_LIMIT, history.size()));
        }
        update.set(ApplicationApproval.Fields.history, history).set(ApplicationApproval.Fields.revision, revision + 1);
        return repository
                .updateIfRevision(state.getId(), revision, update)
                .flatMap(changed -> changed == 0
                        ? badRequest("The approval request was changed by someone else. Reload and try again.")
                        : repository.findById(state.getId()));
    }

    private Mono<String> currentUserEmail() {
        return sessionUserService.getCurrentUser().map(User::getEmail);
    }

    private static ApplicationApprovalDTO toDTO(Application application, ApplicationApproval state) {
        String defaultBasePageId =
                Objects.requireNonNullElse(application.getPages(), List.<ApplicationPage>of()).stream()
                        .filter(ApplicationPage::isDefault)
                        .map(ApplicationPage::getBaseId)
                        .findFirst()
                        .orElse(null);
        String version = state.getFingerprint() == null || state.getStatus() == ApprovalStatus.DRAFT
                ? null
                : state.getFingerprint().substring(0, VERSION_LENGTH);
        return new ApplicationApprovalDTO(
                application.getId(),
                application.getName(),
                application.getWorkspaceId(),
                defaultBasePageId,
                state.getStatus(),
                version,
                state.getSubmittedBy(),
                state.getSubmittedAt(),
                state.getReviewedBy(),
                state.getReviewedAt(),
                state.getStatus() == ApprovalStatus.REJECTED ? state.getComment() : null,
                Objects.requireNonNullElse(state.getHistory(), List.of()));
    }

    private static <T> Mono<T> badRequest(String message) {
        return Mono.error(new AppsmithException(AppsmithError.GENERIC_BAD_REQUEST, message));
    }
}

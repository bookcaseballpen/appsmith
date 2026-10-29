package com.appsmith.server.roles;

import com.appsmith.server.domains.Application;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.solutions.ApplicationPermission;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * What users of an app see is what an APPROVER or ADMIN published. An app counts as live once it has been published
 * manually ({@code lastDeployedAt} is set); until then USER cannot see it at all (see
 * {@link com.appsmith.server.repositories.CustomApplicationRepositoryImpl}), whatever its published copy holds.
 *
 * <p>So a caller without the publish permission (DEV) may still let the server copy edit into view mode on an app that
 * is not live yet (a new, duplicated, forked or imported app), but never on a live one.
 *
 * <p>Every role publishes through the release approval flow: only a publish run inside {@link #asApprovedRelease} (by
 * {@link com.appsmith.server.approvals.ApplicationApprovalService#approve}) may make an app live or change a live app's
 * view mode. The marker lives in the Reactor context, which no request can set.
 */
@Component
public class PublishApproval {

    private static final String APPROVED_RELEASE = PublishApproval.class.getName() + ".approvedRelease";

    private final ApplicationRepository applicationRepository;
    private final ApplicationPermission applicationPermission;

    public PublishApproval(ApplicationRepository applicationRepository, ApplicationPermission applicationPermission) {
        this.applicationRepository = applicationRepository;
        this.applicationPermission = applicationPermission;
    }

    /** Runs {@code publish} as the publish of an approved release. */
    public static <T> Mono<T> asApprovedRelease(Mono<T> publish) {
        return publish.contextWrite(context -> context.put(APPROVED_RELEASE, true));
    }

    public static Mono<Boolean> isApprovedRelease() {
        return Mono.deferContextual(context -> Mono.just(context.getOrDefault(APPROVED_RELEASE, false)));
    }

    public Mono<Boolean> canPublish(String applicationId) {
        return applicationRepository
                .findById(applicationId, applicationPermission.getPublishPermission())
                .hasElement();
    }

    public Mono<Boolean> isLive(String applicationId) {
        return applicationRepository
                .findById(applicationId)
                .map(application -> application.getLastDeployedAt() != null)
                .defaultIfEmpty(false);
    }

    /**
     * Takes an app out of the users' sight until it is published manually, for an app whose view mode was filled by
     * someone who cannot publish (an import carries the exported view mode with it).
     */
    public Mono<Void> markNotLive(String applicationId) {
        return applicationRepository
                .updateByIdWithoutPermissionCheck(
                        applicationId, Bridge.update().set(Application.Fields.lastDeployedAt, null))
                .then();
    }
}

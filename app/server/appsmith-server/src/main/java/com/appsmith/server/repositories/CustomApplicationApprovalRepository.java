package com.appsmith.server.repositories;

import com.appsmith.server.approvals.ApprovalStatus;
import com.appsmith.server.domains.ApplicationApproval;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CustomApplicationApprovalRepository {

    Flux<ApplicationApproval> findAllByStatus(ApprovalStatus status);

    /**
     * Applies the update only if the stored revision is still {@code expectedRevision}; answers the number of documents
     * changed (0 when someone else changed the request first).
     */
    Mono<Integer> updateIfRevision(String id, int expectedRevision, BridgeUpdate update);
}

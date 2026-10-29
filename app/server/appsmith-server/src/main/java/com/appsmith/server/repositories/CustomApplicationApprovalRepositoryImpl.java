package com.appsmith.server.repositories;

import com.appsmith.server.approvals.ApprovalStatus;
import com.appsmith.server.domains.ApplicationApproval;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CustomApplicationApprovalRepositoryImpl extends BaseAppsmithRepositoryImpl<ApplicationApproval>
        implements CustomApplicationApprovalRepository {

    @Override
    public Flux<ApplicationApproval> findAllByStatus(ApprovalStatus status) {
        return queryBuilder()
                .criteria(Bridge.equal(ApplicationApproval.Fields.status, status))
                .all();
    }

    @Override
    public Mono<Integer> updateIfRevision(String id, int expectedRevision, BridgeUpdate update) {
        return queryBuilder()
                .byId(id)
                .criteria(Bridge.equal(ApplicationApproval.Fields.revision, expectedRevision))
                .updateFirst(update);
    }
}

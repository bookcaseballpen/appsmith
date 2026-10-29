package com.appsmith.server.domains;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.approvals.ApprovalEvent;
import com.appsmith.server.approvals.ApprovalStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fork: the release approval state of one application (one branch of a git app). Its id is the application's id, so an
 * application has at most one. Kept apart from {@link Application} so app saves, exports and git files never carry it.
 * See {@link com.appsmith.server.approvals.ApplicationApprovalService}.
 */
@Getter
@Setter
@ToString
@Document
@FieldNameConstants
public class ApplicationApproval extends BaseDomain {

    private String applicationId;

    private String workspaceId;

    private ApprovalStatus status;

    // Fingerprint of the edit-mode version that was submitted (SUBMITTED, REJECTED) or approved (APPROVED).
    private String fingerprint;

    private String submittedBy;

    private Instant submittedAt;

    private String reviewedBy;

    private Instant reviewedAt;

    // The approver's comment on the last rejection.
    private String comment;

    // Bumped on every change, so two concurrent changes cannot both win.
    private Integer revision;

    // Newest last, at most ApplicationApprovalService.HISTORY_LIMIT entries.
    private List<ApprovalEvent> history = new ArrayList<>();
}

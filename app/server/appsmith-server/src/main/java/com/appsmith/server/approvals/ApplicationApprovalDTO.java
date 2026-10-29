package com.appsmith.server.approvals;

import java.time.Instant;
import java.util.List;

/**
 * An application's approval state as the client shows it. {@code version} is the start of the fingerprint of the
 * submitted or approved version; {@code defaultBasePageId} lets the approver's list link to the app's preview.
 */
public record ApplicationApprovalDTO(
        String applicationId,
        String applicationName,
        String workspaceId,
        String defaultBasePageId,
        ApprovalStatus status,
        String version,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String comment,
        List<ApprovalEvent> history) {}

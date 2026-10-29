package com.appsmith.server.approvals;

/**
 * Where an application's release stands. DRAFT also covers an app that never had a request, a request cancelled because
 * the app changed after it was submitted, and an approved app that has changed since.
 */
public enum ApprovalStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
}

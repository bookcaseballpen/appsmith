package com.appsmith.server.approvals;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One line of an application's approval history: who did what, when, and the comment (rejection) or reason
 * (cancellation).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalEvent {

    public enum Type {
        SUBMITTED,
        APPROVED,
        REJECTED,
        CANCELLED,
    }

    private Type type;

    // Email of the user, or null when the server cancelled the request.
    private String by;

    private Instant at;

    private String comment;
}

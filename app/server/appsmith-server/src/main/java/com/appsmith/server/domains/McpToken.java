package com.appsmith.server.domains;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.enums.McpTokenStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;
import org.hibernate.annotations.Where;

import java.time.Instant;

@Getter
@Setter
@ToString
@Entity
@Where(clause = "deleted_at IS NULL")
@FieldNameConstants
public class McpToken extends BaseDomain {

    // Denormalized for authentication without a join to McpKey.
    private String userId;

    // McpKey.id (Mongo `_id`).
    private String mcpKeyId;

    @ToString.Exclude
    private String tokenHash;

    // A given McpKey should have at most one ACTIVE token. Rotate marks that row ROTATED and inserts a new ACTIVE
    // row; revoke marks it REVOKED. Historical ROTATED/REVOKED rows are kept on purpose.
    @Enumerated(EnumType.STRING)
    private McpTokenStatus status;

    private Instant expiresAt;

    // Last rotate or revoke on this token row.
    private Instant actedAt;
}

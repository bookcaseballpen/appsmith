package com.appsmith.server.domains;

import com.appsmith.external.models.BaseDomain;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class UsagePulse extends BaseDomain {

    private String email;

    // Hashed user email
    private String user;
    private String instanceId;
    private String organizationId;
    private Boolean viewMode;
    private Boolean isAnonymousUser;

    @Deprecated
    // TODO: Remove this field once we have migrated the data to use organizationId instead of tenantId
    private String tenantId;
}

package com.appsmith.server.dtos;

import com.appsmith.server.dtos.ce.UserProfileCE_DTO;
import com.appsmith.server.roles.InstanceRole;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserProfileDTO extends UserProfileCE_DTO {

    // The user's instance-wide role; the client hides what the role may not do, the server enforces it.
    InstanceRole role;

    // The single workspace every app lives in.
    String defaultWorkspaceId;
}

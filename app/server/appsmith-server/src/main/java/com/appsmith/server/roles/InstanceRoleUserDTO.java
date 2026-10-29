package com.appsmith.server.roles;

import com.appsmith.server.domains.User;

/**
 * A user and their {@link InstanceRole}, as listed on the administrators' role page.
 */
public record InstanceRoleUserDTO(String id, String email, String name, InstanceRole role) {

    public static InstanceRoleUserDTO of(User user, InstanceRole role) {
        return new InstanceRoleUserDTO(user.getId(), user.getEmail(), user.getName(), role);
    }
}

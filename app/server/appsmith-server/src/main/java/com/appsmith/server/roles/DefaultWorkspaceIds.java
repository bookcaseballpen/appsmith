package com.appsmith.server.roles;

import java.util.Collection;
import java.util.Map;

/**
 * The ids of the single default workspace and of its four role groups.
 */
public record DefaultWorkspaceIds(String workspaceId, Map<InstanceRole, String> groupIds) {

    public String groupId(InstanceRole role) {
        return groupIds.get(role);
    }

    /**
     * The highest role whose group is among the given permission group ids, or USER when there is none.
     */
    public InstanceRole roleOf(Collection<String> permissionGroupIds) {
        for (InstanceRole role : InstanceRole.values()) {
            if (permissionGroupIds.contains(groupIds.get(role))) {
                return role;
            }
        }
        return InstanceRole.USER;
    }
}

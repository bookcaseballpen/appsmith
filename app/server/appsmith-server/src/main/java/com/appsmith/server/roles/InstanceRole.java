package com.appsmith.server.roles;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.AppsmithRole;
import lombok.Getter;

import java.util.Set;

import static com.appsmith.server.acl.AclPermission.READ_WORKSPACES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_CREATE_APPLICATION;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_PUBLISH_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_DATASOURCES;

/**
 * The four fixed, instance-wide roles of this fork. Each role is one permission group on the single default workspace
 * (see {@link DefaultWorkspaceService}); ADMIN is also an instance administrator.
 *
 * <p>The workspace permissions below are expanded into application, page, action and datasource policies by the
 * {@link com.appsmith.server.acl.PolicyGenerator} graph:
 * <ul>
 *   <li>DEV creates apps and uses the datasources (read, create queries, execute) but never manages them, and has no
 *       publish permission. A DEV sees and edits no app through the role: only the apps they hold a grant on (see
 *       {@link com.appsmith.server.grants.AppGrantService}), which includes every app they create.</li>
 *   <li>APPROVER holds manage on every app only so the editor can load the unpublished version; every write other than
 *       publish is refused by {@link ApproverReadOnlyWebFilter}.</li>
 *   <li>USER executes the datasources and reads no app through the role: only the live apps they hold a grant on, in
 *       view mode.</li>
 * </ul>
 *
 * <p>Changing these sets changes what the role groups store and what existing documents hold; the server brings both
 * in line at startup ({@link RoleGroupReconciler}).
 */
@Getter
public enum InstanceRole {
    ADMIN(
            "Administrator",
            "Manages everything: apps, datasources, users and roles",
            AppsmithRole.WORKSPACE_ADMIN.getPermissions()),
    DEV(
            "Developer",
            "Builds apps and edits the apps they are granted; uses existing datasources; cannot publish or manage"
                    + " datasources",
            Set.of(
                    READ_WORKSPACES,
                    WORKSPACE_CREATE_APPLICATION,
                    WORKSPACE_READ_DATASOURCES,
                    WORKSPACE_EXECUTE_DATASOURCES)),
    APPROVER(
            "Approver",
            "Reviews apps read-only and publishes them",
            Set.of(
                    READ_WORKSPACES,
                    WORKSPACE_MANAGE_APPLICATIONS,
                    WORKSPACE_READ_APPLICATIONS,
                    WORKSPACE_PUBLISH_APPLICATIONS,
                    WORKSPACE_EXECUTE_DATASOURCES)),
    USER(
            "App Viewer",
            "Runs the published apps they are granted",
            Set.of(READ_WORKSPACES, WORKSPACE_EXECUTE_DATASOURCES)),
    ;

    private final String groupName;
    private final String description;
    private final Set<AclPermission> workspacePermissions;

    InstanceRole(String groupName, String description, Set<AclPermission> workspacePermissions) {
        this.groupName = groupName;
        this.description = description;
        this.workspacePermissions = workspacePermissions;
    }

    public boolean canEdit() {
        return this == ADMIN || this == DEV;
    }
}

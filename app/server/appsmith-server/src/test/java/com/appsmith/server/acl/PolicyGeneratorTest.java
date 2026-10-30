package com.appsmith.server.acl;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.appsmith.server.acl.AclPermission.APPLICATION_CREATE_PAGES;
import static com.appsmith.server.acl.AclPermission.CONNECT_TO_GIT;
import static com.appsmith.server.acl.AclPermission.CREATE_DATASOURCE_ACTIONS;
import static com.appsmith.server.acl.AclPermission.DELETE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.MANAGE_WORKSPACES;
import static com.appsmith.server.acl.AclPermission.PUBLISH_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.READ_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.READ_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_CREATE_APPLICATION;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_DELETE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_PUBLISH_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_DATASOURCES;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyGeneratorTest {

    private PolicyGenerator policyGenerator;

    @BeforeEach
    void setUp() {
        policyGenerator = new PolicyGenerator();
        policyGenerator.createPolicyGraph();
    }

    @Test
    void should_notImplyPublish_when_workspaceAppsAreManaged() {
        // When
        Set<AclPermission> workspacePermissions =
                policyGenerator.getChildPermissions(WORKSPACE_MANAGE_APPLICATIONS, Workspace.class);
        Set<AclPermission> creatorPermissions =
                policyGenerator.getChildPermissions(WORKSPACE_CREATE_APPLICATION, Workspace.class);

        // Then
        assertThat(workspacePermissions).doesNotContain(WORKSPACE_PUBLISH_APPLICATIONS);
        assertThat(creatorPermissions).doesNotContain(WORKSPACE_PUBLISH_APPLICATIONS);
    }

    @Test
    void should_keepPublish_when_workspaceIsManaged() {
        // When
        Set<AclPermission> permissions = policyGenerator.getChildPermissions(MANAGE_WORKSPACES, Workspace.class);

        // Then
        assertThat(permissions)
                .contains(WORKSPACE_PUBLISH_APPLICATIONS, WORKSPACE_MANAGE_DATASOURCES, WORKSPACE_DELETE_APPLICATIONS);
    }

    @Test
    void should_notImplyDelete_when_workspaceAppsAreCreatedOrManaged() {
        // When
        Set<AclPermission> creatorPermissions =
                policyGenerator.getChildPermissions(WORKSPACE_CREATE_APPLICATION, Workspace.class);
        Set<AclPermission> managerPermissions =
                policyGenerator.getChildPermissions(WORKSPACE_MANAGE_APPLICATIONS, Workspace.class);

        // Then
        assertThat(creatorPermissions).doesNotContain(WORKSPACE_DELETE_APPLICATIONS);
        assertThat(managerPermissions).doesNotContain(WORKSPACE_DELETE_APPLICATIONS);
    }

    @Test
    void should_giveNoAppRights_when_workspaceAppsAreOnlyCreated() {
        // When
        Set<AclPermission> workspacePermissions =
                policyGenerator.getChildPermissions(WORKSPACE_CREATE_APPLICATION, Workspace.class);
        Set<AclPermission> applicationPermissions =
                policyGenerator.getChildPermissions(WORKSPACE_CREATE_APPLICATION, Application.class);

        // Then
        assertThat(workspacePermissions).doesNotContain(WORKSPACE_MANAGE_APPLICATIONS, WORKSPACE_READ_APPLICATIONS);
        assertThat(applicationPermissions).isEmpty();
    }

    @Test
    void should_keepPageAndGitRights_when_workspaceAppsAreManaged() {
        // When
        Set<AclPermission> permissions =
                policyGenerator.getChildPermissions(WORKSPACE_MANAGE_APPLICATIONS, Application.class);

        // Then
        assertThat(permissions)
                .contains(MANAGE_APPLICATIONS, READ_APPLICATIONS, APPLICATION_CREATE_PAGES, CONNECT_TO_GIT)
                .doesNotContain(PUBLISH_APPLICATIONS);
    }

    @Test
    void should_executeButNotReadDatasources_when_workspaceDatasourcesAreExecuted() {
        // When
        Set<AclPermission> permissions =
                policyGenerator.getChildPermissions(WORKSPACE_EXECUTE_DATASOURCES, Datasource.class);

        // Then
        assertThat(permissions).contains(EXECUTE_DATASOURCES).doesNotContain(READ_DATASOURCES, MANAGE_DATASOURCES);
    }

    @Test
    void should_notGiveDatasourceManage_when_workspaceAppsAreManaged() {
        // When
        Set<AclPermission> permissions =
                policyGenerator.getChildPermissions(WORKSPACE_MANAGE_APPLICATIONS, Datasource.class);

        // Then
        assertThat(permissions).doesNotContain(MANAGE_DATASOURCES, DELETE_DATASOURCES);
    }

    @Test
    void should_giveUseButNotManage_when_workspaceDatasourcesAreRead() {
        // When
        Set<AclPermission> permissions =
                policyGenerator.getChildPermissions(WORKSPACE_READ_DATASOURCES, Datasource.class);

        // Then
        assertThat(permissions)
                .contains(READ_DATASOURCES, CREATE_DATASOURCE_ACTIONS, EXECUTE_DATASOURCES)
                .doesNotContain(MANAGE_DATASOURCES, DELETE_DATASOURCES);
    }

    @Test
    void should_giveFullDatasourceRights_when_workspaceDatasourcesAreManaged() {
        // When
        Set<AclPermission> permissions =
                policyGenerator.getChildPermissions(WORKSPACE_MANAGE_DATASOURCES, Datasource.class);

        // Then
        assertThat(permissions)
                .contains(MANAGE_DATASOURCES, READ_DATASOURCES, CREATE_DATASOURCE_ACTIONS, EXECUTE_DATASOURCES);
    }
}

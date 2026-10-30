package com.appsmith.server.acl;

import com.appsmith.server.acl.ce.PolicyGeneratorCE;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.appsmith.server.acl.AclPermission.APPLICATION_CREATE_PAGES;
import static com.appsmith.server.acl.AclPermission.CONNECT_TO_GIT;
import static com.appsmith.server.acl.AclPermission.CREATE_DATASOURCE_ACTIONS;
import static com.appsmith.server.acl.AclPermission.EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.MANAGE_AUTO_COMMIT;
import static com.appsmith.server.acl.AclPermission.MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.MANAGE_DEFAULT_BRANCHES;
import static com.appsmith.server.acl.AclPermission.MANAGE_PROTECTED_BRANCHES;
import static com.appsmith.server.acl.AclPermission.READ_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_CREATE_APPLICATION;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_DELETE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_PUBLISH_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_DATASOURCES;

/**
 * Fork changes to the permission graph, for the roles in {@link com.appsmith.server.roles.InstanceRole}:
 * <ul>
 *   <li>Managing apps no longer implies publishing them, so a developer can edit but not deploy.</li>
 *   <li>Creating apps implies neither deleting nor managing them, so a developer edits only the apps they hold an
 *       Edit grant on (see {@link com.appsmith.server.grants.AppGrantService}) and only an administrator deletes
 *       apps. The app rights CE derives from creating apps (pages, git) come from managing every app instead.</li>
 *   <li>Datasource management comes from the workspace datasource permission instead of from managing apps.</li>
 *   <li>Reading the workspace's datasources gives "use" rights: read, create queries on, and execute them.</li>
 *   <li>Executing the workspace's datasources executes every datasource, without reading every app.</li>
 * </ul>
 * The CE roles keep their effective permissions: they hold publish, app management and deletion and datasource
 * management explicitly.
 */
@Component
public class PolicyGenerator extends PolicyGeneratorCE {

    private static final List<AclPermission> APP_RIGHTS_FROM_CREATING_APPS = List.of(
            APPLICATION_CREATE_PAGES,
            CONNECT_TO_GIT,
            MANAGE_PROTECTED_BRANCHES,
            MANAGE_DEFAULT_BRANCHES,
            MANAGE_AUTO_COMMIT);

    @Override
    protected void createWorkspacePolicyGraph() {
        super.createWorkspacePolicyGraph();
        lateralGraph.removeEdge(WORKSPACE_MANAGE_APPLICATIONS, WORKSPACE_PUBLISH_APPLICATIONS);
        lateralGraph.removeEdge(WORKSPACE_CREATE_APPLICATION, WORKSPACE_DELETE_APPLICATIONS);
        lateralGraph.removeEdge(WORKSPACE_CREATE_APPLICATION, WORKSPACE_MANAGE_APPLICATIONS);
    }

    @Override
    protected void createDatasourcePolicyGraph() {
        super.createDatasourcePolicyGraph();
        hierarchyGraph.removeEdge(WORKSPACE_MANAGE_APPLICATIONS, MANAGE_DATASOURCES);
        hierarchyGraph.addEdge(WORKSPACE_MANAGE_DATASOURCES, MANAGE_DATASOURCES);
        hierarchyGraph.addEdge(WORKSPACE_READ_DATASOURCES, READ_DATASOURCES);
        hierarchyGraph.addEdge(WORKSPACE_EXECUTE_DATASOURCES, EXECUTE_DATASOURCES);

        lateralGraph.addEdge(MANAGE_DATASOURCES, CREATE_DATASOURCE_ACTIONS);
        lateralGraph.addEdge(READ_DATASOURCES, CREATE_DATASOURCE_ACTIONS);
        lateralGraph.addEdge(CREATE_DATASOURCE_ACTIONS, EXECUTE_DATASOURCES);
    }

    @Override
    protected void createApplicationPolicyGraph() {
        super.createApplicationPolicyGraph();
        for (AclPermission permission : APP_RIGHTS_FROM_CREATING_APPS) {
            hierarchyGraph.removeEdge(WORKSPACE_CREATE_APPLICATION, permission);
            hierarchyGraph.addEdge(WORKSPACE_MANAGE_APPLICATIONS, permission);
        }
    }
}

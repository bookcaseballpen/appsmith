package com.appsmith.server.acl;

import com.appsmith.server.acl.ce.PolicyGeneratorCE;
import org.springframework.stereotype.Component;

import static com.appsmith.server.acl.AclPermission.CREATE_DATASOURCE_ACTIONS;
import static com.appsmith.server.acl.AclPermission.EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.READ_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_CREATE_APPLICATION;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_DELETE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_PUBLISH_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_DATASOURCES;

/**
 * Fork changes to the permission graph, for the roles in {@link com.appsmith.server.roles.InstanceRole}:
 * <ul>
 *   <li>Managing apps no longer implies publishing them, so a developer can edit but not deploy.</li>
 *   <li>Creating apps no longer implies deleting them, so only an administrator deletes apps.</li>
 *   <li>Datasource management comes from the workspace datasource permission instead of from managing apps.</li>
 *   <li>Reading the workspace's datasources gives "use" rights: read, create queries on, and execute them.</li>
 * </ul>
 * The CE roles keep their effective permissions: they hold publish, app deletion and datasource management explicitly.
 */
@Component
public class PolicyGenerator extends PolicyGeneratorCE {

    @Override
    protected void createWorkspacePolicyGraph() {
        super.createWorkspacePolicyGraph();
        lateralGraph.removeEdge(WORKSPACE_MANAGE_APPLICATIONS, WORKSPACE_PUBLISH_APPLICATIONS);
        lateralGraph.removeEdge(WORKSPACE_CREATE_APPLICATION, WORKSPACE_DELETE_APPLICATIONS);
    }

    @Override
    protected void createDatasourcePolicyGraph() {
        super.createDatasourcePolicyGraph();
        hierarchyGraph.removeEdge(WORKSPACE_MANAGE_APPLICATIONS, MANAGE_DATASOURCES);
        hierarchyGraph.addEdge(WORKSPACE_MANAGE_DATASOURCES, MANAGE_DATASOURCES);
        hierarchyGraph.addEdge(WORKSPACE_READ_DATASOURCES, READ_DATASOURCES);

        lateralGraph.addEdge(MANAGE_DATASOURCES, CREATE_DATASOURCE_ACTIONS);
        lateralGraph.addEdge(READ_DATASOURCES, CREATE_DATASOURCE_ACTIONS);
        lateralGraph.addEdge(CREATE_DATASOURCE_ACTIONS, EXECUTE_DATASOURCES);
    }
}

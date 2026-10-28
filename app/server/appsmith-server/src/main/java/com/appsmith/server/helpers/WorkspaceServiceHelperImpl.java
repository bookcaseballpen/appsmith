package com.appsmith.server.helpers;

import com.appsmith.server.helpers.ce.WorkspaceServiceHelperCEImpl;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static java.lang.Boolean.FALSE;

/**
 * This fork has one workspace for everyone (see {@link com.appsmith.server.roles.DefaultWorkspaceService}, which
 * creates it straight through the repository). Nobody gets a personal workspace, and only an ADMIN may create another
 * workspace, so a USER or DEV cannot become the administrator of a workspace of their own.
 */
@Component
public class WorkspaceServiceHelperImpl extends WorkspaceServiceHelperCEImpl implements WorkspaceServiceHelper {

    private final InstanceRoleService instanceRoleService;

    // Lazy: the role service reaches the workspace services through UserUtils and the repositories.
    public WorkspaceServiceHelperImpl(@Lazy InstanceRoleService instanceRoleService) {
        this.instanceRoleService = instanceRoleService;
    }

    /**
     * A personal ("default") workspace is never created, which also makes the OAuth signup skip its first app. Any
     * other workspace needs the ADMIN role.
     */
    @Override
    public Mono<Boolean> isCreateWorkspaceAllowed(Boolean isDefaultWorkspace) {
        if (Boolean.TRUE.equals(isDefaultWorkspace)) {
            return Mono.just(FALSE);
        }
        return instanceRoleService.getCurrentUserRole().map(role -> role == InstanceRole.ADMIN);
    }
}

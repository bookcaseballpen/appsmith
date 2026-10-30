package com.appsmith.server.solutions;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.solutions.ce.WorkspacePermissionCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class WorkspacePermissionImpl extends WorkspacePermissionCEImpl implements WorkspacePermission {

    /**
     * CE creates an app in a workspace with "manage every app" on it. Here a DEV creates apps without managing the
     * others (see {@link com.appsmith.server.grants.AppGrantService}), so creating needs the create permission, which
     * ADMIN, DEV and the CE administrator and developer roles hold.
     */
    @Override
    public Mono<AclPermission> getApplicationCreatePermission() {
        return Mono.just(AclPermission.WORKSPACE_CREATE_APPLICATION);
    }
}

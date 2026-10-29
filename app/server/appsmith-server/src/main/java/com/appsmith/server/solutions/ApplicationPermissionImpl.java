package com.appsmith.server.solutions;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.solutions.ce.ApplicationPermissionCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ApplicationPermissionImpl extends ApplicationPermissionCEImpl implements ApplicationPermission {

    /**
     * CE deletes an app with manage permission. Here deleting needs the delete permission, which only the ADMIN role
     * holds (see {@link com.appsmith.server.acl.PolicyGenerator}); the CE workspace roles hold it explicitly.
     */
    @Override
    public Mono<AclPermission> getDeletePermission() {
        return Mono.just(AclPermission.DELETE_APPLICATIONS);
    }
}

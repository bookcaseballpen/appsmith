package com.appsmith.server.solutions;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.solutions.ce.DatasourcePermissionCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class DatasourcePermissionImpl extends DatasourcePermissionCEImpl implements DatasourcePermission {

    /**
     * Creating a query on a datasource only needs "use" rights, so a developer can build queries on a datasource they
     * cannot manage.
     */
    @Override
    public Mono<AclPermission> getActionCreatePermission() {
        return Mono.just(AclPermission.CREATE_DATASOURCE_ACTIONS);
    }

    /**
     * CE lets anyone who can read a datasource export it with its configuration. Here every non-git export of a
     * datasource needs manage, so only an administrator ever exports connection settings.
     */
    @Override
    public AclPermission getExportPermission(boolean isGitSync, boolean exportWithConfiguration) {
        return isGitSync ? null : getEditPermission();
    }
}

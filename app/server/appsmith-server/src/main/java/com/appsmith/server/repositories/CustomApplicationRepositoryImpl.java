package com.appsmith.server.repositories;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Application;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.ce.CustomApplicationRepositoryCEImpl;
import com.appsmith.server.roles.DefaultWorkspaceIds;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.solutions.ApplicationPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class CustomApplicationRepositoryImpl extends CustomApplicationRepositoryCEImpl
        implements CustomApplicationRepository {

    private final DefaultWorkspaceService defaultWorkspaceService;

    // Lazy: the default workspace service reaches this repository through PolicySolution.
    @Autowired
    public CustomApplicationRepositoryImpl(
            CacheableRepositoryHelper cacheableRepositoryHelper,
            ApplicationPermission applicationPermission,
            @Lazy DefaultWorkspaceService defaultWorkspaceService) {
        super(cacheableRepositoryHelper, applicationPermission);
        this.defaultWorkspaceService = defaultWorkspaceService;
    }

    /**
     * Users (members of the USER role group of the default workspace) only see the default workspace's apps that an
     * approver or administrator has published at least once ({@code lastDeployedAt} is set), whatever app grant they
     * hold. Every read of an application with READ_APPLICATIONS goes through here, so a never-published app is hidden
     * from the app list and from every API that loads it. Other roles, and apps in any other workspace, are unchanged.
     */
    @Override
    protected Query createQueryWithPermission(
            List<Criteria> criterias,
            List<String> projectionFieldNames,
            Set<String> permissionGroups,
            AclPermission aclPermission) {
        DefaultWorkspaceIds ids = defaultWorkspaceService.getResolvedIds().orElse(null);
        if (aclPermission != AclPermission.READ_APPLICATIONS
                || ids == null
                || permissionGroups == null
                || !permissionGroups.contains(ids.groupId(InstanceRole.USER))) {
            return super.createQueryWithPermission(criterias, projectionFieldNames, permissionGroups, aclPermission);
        }

        List<Criteria> liveOnly = new ArrayList<>(criterias);
        liveOnly.add(Bridge.<Application>or(
                Bridge.isNotNull(Application.Fields.lastDeployedAt),
                Bridge.<Application>query().notEqual(Application.Fields.workspaceId, ids.workspaceId())));
        return super.createQueryWithPermission(liveOnly, projectionFieldNames, permissionGroups, aclPermission);
    }
}

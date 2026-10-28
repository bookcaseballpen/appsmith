package com.appsmith.server.repositories;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.domains.Application;
import com.appsmith.server.repositories.ce.CustomApplicationRepositoryCEImpl;
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
import java.util.HashSet;
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
     * Users (the USER role group of the default workspace) only see apps that an approver or administrator has
     * published at least once ({@code lastDeployedAt} is set). Every read of an application with READ_APPLICATIONS
     * goes through here, so a never-published app is hidden from the app list and from every API that loads it.
     * Read access through any other group (a builder role, a public app) is unchanged.
     */
    @Override
    protected Query createQueryWithPermission(
            List<Criteria> criterias,
            List<String> projectionFieldNames,
            Set<String> permissionGroups,
            AclPermission aclPermission) {
        String viewerGroupId = defaultWorkspaceService
                .getResolvedIds()
                .map(ids -> ids.groupId(InstanceRole.USER))
                .orElse(null);
        if (aclPermission != AclPermission.READ_APPLICATIONS
                || viewerGroupId == null
                || permissionGroups == null
                || !permissionGroups.contains(viewerGroupId)) {
            return super.createQueryWithPermission(criterias, projectionFieldNames, permissionGroups, aclPermission);
        }

        Criteria readAsViewer = new Criteria()
                .andOperator(
                        userAcl(Set.of(viewerGroupId), aclPermission),
                        Criteria.where(Application.Fields.lastDeployedAt).ne(null));
        Set<String> otherGroups = new HashSet<>(permissionGroups);
        otherGroups.remove(viewerGroupId);
        Criteria read = otherGroups.isEmpty()
                ? readAsViewer
                : new Criteria().orOperator(userAcl(otherGroups, aclPermission), readAsViewer);

        List<Criteria> withRead = new ArrayList<>(criterias);
        withRead.add(read);
        // The permission check is in withRead already.
        return super.createQueryWithPermission(withRead, projectionFieldNames, permissionGroups, null);
    }
}

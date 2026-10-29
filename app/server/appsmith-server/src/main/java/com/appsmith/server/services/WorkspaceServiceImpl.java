package com.appsmith.server.services;

import com.appsmith.server.domains.Workspace;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.WorkspaceServiceHelper;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.AssetRepository;
import com.appsmith.server.repositories.PluginRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.services.ce.WorkspaceServiceCEImpl;
import com.appsmith.server.solutions.PermissionGroupPermission;
import com.appsmith.server.solutions.PolicySolution;
import com.appsmith.server.solutions.WorkspacePermission;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class WorkspaceServiceImpl extends WorkspaceServiceCEImpl implements WorkspaceService {

    private final DefaultWorkspaceService defaultWorkspaceService;

    public WorkspaceServiceImpl(
            Validator validator,
            WorkspaceRepository repository,
            AnalyticsService analyticsService,
            PluginRepository pluginRepository,
            SessionUserService sessionUserService,
            AssetRepository assetRepository,
            AssetService assetService,
            ApplicationRepository applicationRepository,
            PermissionGroupService permissionGroupService,
            PolicySolution policySolution,
            ModelMapper modelMapper,
            WorkspacePermission workspacePermission,
            PermissionGroupPermission permissionGroupPermission,
            WorkspaceServiceHelper workspaceServiceHelper,
            DefaultWorkspaceService defaultWorkspaceService) {

        super(
                validator,
                repository,
                analyticsService,
                pluginRepository,
                sessionUserService,
                assetRepository,
                assetService,
                applicationRepository,
                permissionGroupService,
                policySolution,
                modelMapper,
                workspacePermission,
                permissionGroupPermission,
                workspaceServiceHelper);
        this.defaultWorkspaceService = defaultWorkspaceService;
    }

    /**
     * The default workspace holds the role groups every user's role comes from, so it is never deleted.
     */
    @Override
    public Mono<Workspace> archiveById(String workspaceId) {
        return defaultWorkspaceService
                .getIds()
                .flatMap(ids -> ids.workspaceId().equals(workspaceId)
                        ? Mono.error(new AppsmithException(
                                AppsmithError.ACTION_IS_NOT_AUTHORIZED, "Delete the default workspace"))
                        : super.archiveById(workspaceId));
    }
}

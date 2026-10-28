package com.appsmith.server.datasources.base;

import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceStorageDTO;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.datasourcestorages.base.DatasourceStorageService;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.ratelimiting.RateLimitService;
import com.appsmith.server.repositories.DatasourceRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.roles.DatasourceRedactor;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.DatasourceContextService;
import com.appsmith.server.services.FeatureFlagService;
import com.appsmith.server.services.SequenceService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.solutions.DatasourcePermission;
import com.appsmith.server.solutions.EnvironmentPermission;
import com.appsmith.server.solutions.WorkspacePermission;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

/**
 * Datasource connections are configured by the people who can manage them; in the default workspace that is the
 * ADMIN role only (see {@link com.appsmith.server.roles.InstanceRole}). Create, update and delete already require
 * manage in CE. This class closes the two paths where CE settles for less.
 */
@Slf4j
@Service
public class DatasourceServiceImpl extends DatasourceServiceCEImpl implements DatasourceService {

    private final DatasourceRepository datasourceRepository;
    private final DatasourcePermission datasourcePermission;

    public DatasourceServiceImpl(
            DatasourceRepository repository,
            WorkspaceService workspaceService,
            AnalyticsService analyticsService,
            SessionUserService sessionUserService,
            PluginService pluginService,
            PluginExecutorHelper pluginExecutorHelper,
            PolicyGenerator policyGenerator,
            SequenceService sequenceService,
            NewActionRepository newActionRepository,
            DatasourceContextService datasourceContextService,
            DatasourcePermission datasourcePermission,
            WorkspacePermission workspacePermission,
            DatasourceStorageService datasourceStorageService,
            EnvironmentPermission environmentPermission,
            RateLimitService rateLimitService,
            FeatureFlagService featureFlagService,
            ObservationRegistry observationRegistry) {

        super(
                repository,
                workspaceService,
                analyticsService,
                sessionUserService,
                pluginService,
                pluginExecutorHelper,
                policyGenerator,
                sequenceService,
                newActionRepository,
                datasourceContextService,
                datasourcePermission,
                workspacePermission,
                datasourceStorageService,
                environmentPermission,
                rateLimitService,
                featureFlagService,
                observationRegistry);
        this.datasourceRepository = repository;
        this.datasourcePermission = datasourcePermission;
    }

    /**
     * CE tests a saved datasource with read permission only, while the request may carry its own host and the server
     * fills in the stored password. That would let someone who cannot manage the datasource send its credentials to
     * a host of their choice, so a saved datasource is tested only by those who can manage it.
     */
    @Override
    public Mono<DatasourceTestResult> testDatasource(
            DatasourceStorageDTO datasourceStorageDTO, String activeEnvironmentId) {
        String datasourceId = datasourceStorageDTO == null ? null : datasourceStorageDTO.getDatasourceId();
        if (!hasText(datasourceId)) {
            return super.testDatasource(datasourceStorageDTO, activeEnvironmentId);
        }
        return datasourceRepository
                .findById(datasourceId, datasourcePermission.getEditPermission())
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.DATASOURCE, datasourceId)))
                .then(Mono.defer(() -> super.testDatasource(datasourceStorageDTO, activeEnvironmentId)));
    }

    /**
     * The datasource list used by the editor (directly and through the consolidated API) and by MCP. A datasource the
     * user cannot manage comes back with name and plugin only, never its connection settings or credentials.
     */
    @Override
    public Flux<Datasource> getAllWithStorages(MultiValueMap<String, String> params) {
        String workspaceId = params.getFirst(Datasource.Fields.workspaceId);
        if (workspaceId == null) {
            return super.getAllWithStorages(params);
        }
        return getManageableDatasourceIds(workspaceId).flatMapMany(manageableIds -> super.getAllWithStorages(params)
                .map(datasource -> manageableIds.contains(datasource.getId())
                        ? datasource
                        : DatasourceRedactor.redact(datasource)));
    }

    private Mono<Set<String>> getManageableDatasourceIds(String workspaceId) {
        return datasourceRepository
                .findAllByWorkspaceId(workspaceId, datasourcePermission.getEditPermission())
                .map(Datasource::getId)
                .collect(Collectors.toSet());
    }
}

package com.appsmith.server.imports.internal;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.converters.ArtifactExchangeJsonAdapter;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.Artifact;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.dtos.ApplicationImportDTO;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.ArtifactExchangeJson;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.imports.importable.ImportableService;
import com.appsmith.server.imports.internal.artifactbased.ArtifactBasedImportService;
import com.appsmith.server.migrations.JsonSchemaMigration;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.roles.PublishApproval;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.WorkspaceService;
import com.google.gson.GsonBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * An exported app carries its view mode with it, so importing over an app replaces what its users see. Over a live app
 * (see {@link PublishApproval}) that needs the publish permission, which DEV lacks. Importing a new app stays open: it
 * is not live until an approver publishes it.
 */
@Service
public class ImportServiceImpl extends ImportServiceCEImpl implements ImportService {

    private final PublishApproval publishApproval;

    public ImportServiceImpl(
            ArtifactBasedImportService<Application, ApplicationImportDTO, ApplicationJson> applicationImportService,
            SessionUserService sessionUserService,
            WorkspaceService workspaceService,
            PermissionGroupRepository permissionGroupRepository,
            TransactionalOperator transactionalOperator,
            AnalyticsService analyticsService,
            ImportableService<Plugin> pluginImportableService,
            ImportableService<Datasource> datasourceImportableService,
            GsonBuilder gsonBuilder,
            ArtifactExchangeJsonAdapter artifactExchangeJsonAdapter,
            JsonSchemaMigration jsonSchemaMigration,
            PublishApproval publishApproval) {
        super(
                applicationImportService,
                sessionUserService,
                workspaceService,
                permissionGroupRepository,
                transactionalOperator,
                analyticsService,
                pluginImportableService,
                datasourceImportableService,
                gsonBuilder,
                artifactExchangeJsonAdapter,
                jsonSchemaMigration);
        this.publishApproval = publishApproval;
    }

    @Override
    public Mono<? extends Artifact> updateNonGitConnectedArtifactFromJson(
            String workspaceId, String artifactId, ArtifactExchangeJson artifactExchangeJson) {
        return requirePublishIfLive(artifactId, "Import over a published application")
                .flatMap(wasLive -> takeOfflineIf(
                        wasLive,
                        artifactId,
                        super.updateNonGitConnectedArtifactFromJson(workspaceId, artifactId, artifactExchangeJson)));
    }

    @Override
    public Mono<? extends Artifact> restoreSnapshot(
            String workspaceId, String branchedArtifactId, ArtifactExchangeJson artifactExchangeJson) {
        return requirePublishIfLive(branchedArtifactId, "Restore a published application")
                .flatMap(wasLive -> takeOfflineIf(
                        wasLive,
                        branchedArtifactId,
                        super.restoreSnapshot(workspaceId, branchedArtifactId, artifactExchangeJson)));
    }

    /** Answers whether the app is live, after refusing a caller without the publish permission on a live app. */
    private Mono<Boolean> requirePublishIfLive(String applicationId, String action) {
        if (!StringUtils.hasText(applicationId)) {
            return Mono.just(false);
        }
        return publishApproval.isLive(applicationId).flatMap(isLive -> !isLive
                ? Mono.just(false)
                : publishApproval
                        .canPublish(applicationId)
                        .flatMap(allowed -> allowed
                                ? Mono.just(true)
                                : Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, action))));
    }

    /**
     * The import carries the file's view mode with it, which nobody approved, so a live app is taken out of users'
     * sight until a release of it is approved.
     */
    private Mono<Artifact> takeOfflineIf(boolean wasLive, String applicationId, Mono<? extends Artifact> imported) {
        return imported.flatMap(artifact -> wasLive
                ? publishApproval.markNotLive(applicationId).thenReturn((Artifact) artifact)
                : Mono.just((Artifact) artifact));
    }
}

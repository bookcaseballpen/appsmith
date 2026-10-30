package com.appsmith.server.imports.internal;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.approvals.ApprovalFingerprint;
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
import com.appsmith.server.projectsrepo.EditCopyJson;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.roles.PublishApproval;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.WorkspaceService;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * Importing a file over an existing app replaces its edit copy only. The published side of the file (what a publish
 * would copy: published pages, queries, JS objects, app settings and theme) is dropped before the import
 * ({@link EditCopyJson#dropPublishedSide}), and the importers keep the stored published copy wherever the file has
 * none. So a DEV, or an AI through MCP, may import over
 * a live app: its users keep seeing the approved version, which changes only through Submit -> Approve (see
 * {@link PublishApproval}). A pending request is cancelled by the approval fingerprint once the edit copy differs.
 *
 * <p>Over a live app the view-mode fingerprint is compared before and after the import; if the import changed what
 * users see anyway, the app is taken offline (as approve does), so users never see an unapproved version.
 *
 * <p>Restoring a snapshot still carries the snapshot's view mode: over a live app it needs the publish permission,
 * which DEV lacks, and takes the app offline until a release of it is approved. Importing a new app stays open: it is
 * not live until an approver publishes it.
 */
@Slf4j
@Service
public class ImportServiceImpl extends ImportServiceCEImpl implements ImportService {

    private final PublishApproval publishApproval;
    private final ApprovalFingerprint approvalFingerprint;

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
            PublishApproval publishApproval,
            ApprovalFingerprint approvalFingerprint) {
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
        this.approvalFingerprint = approvalFingerprint;
    }

    @Override
    public Mono<? extends Artifact> updateNonGitConnectedArtifactFromJson(
            String workspaceId, String artifactId, ArtifactExchangeJson artifactExchangeJson) {
        if (!StringUtils.hasText(artifactId)) {
            return super.updateNonGitConnectedArtifactFromJson(workspaceId, artifactId, artifactExchangeJson);
        }
        if (artifactExchangeJson instanceof ApplicationJson applicationJson) {
            EditCopyJson.dropPublishedSide(applicationJson);
        }
        Mono<Artifact> imported = Mono.defer(
                () -> super.updateNonGitConnectedArtifactFromJson(workspaceId, artifactId, artifactExchangeJson)
                        .map(artifact -> (Artifact) artifact));
        return publishApproval
                .isLive(artifactId)
                .flatMap(isLive -> isLive ? keepingViewMode(artifactId, imported) : imported);
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

    /** Runs the import of a live app and takes the app offline if what its users see changed after all. */
    private Mono<Artifact> keepingViewMode(String applicationId, Mono<Artifact> imported) {
        return approvalFingerprint
                .ofViewMode(applicationId)
                .defaultIfEmpty("")
                .flatMap(before -> imported.flatMap(artifact -> approvalFingerprint
                        .ofViewMode(applicationId)
                        .defaultIfEmpty("")
                        .flatMap(after -> {
                            if (before.equals(after)) {
                                return Mono.just(artifact);
                            }
                            log.warn(
                                    "Import over live application {} changed its published copy; taking it offline",
                                    applicationId);
                            return publishApproval.markNotLive(applicationId).thenReturn(artifact);
                        })));
    }

    /** Answers whether the app is live, after refusing a caller without the publish permission on a live app. */
    private Mono<Boolean> requirePublishIfLive(String applicationId, String action) {
        if (!StringUtils.hasText(applicationId)) {
            return Mono.just(false);
        }
        return publishApproval
                .isLive(applicationId)
                .flatMap(isLive -> !isLive
                        ? Mono.just(false)
                        : publishApproval
                                .canPublish(applicationId)
                                .flatMap(allowed -> allowed
                                        ? Mono.just(true)
                                        : Mono.error(new AppsmithException(
                                                AppsmithError.ACTION_IS_NOT_AUTHORIZED, action))));
    }

    /**
     * A snapshot carries its own view mode, which nobody approved, so a live app is taken out of users' sight until a
     * release of it is approved.
     */
    private Mono<Artifact> takeOfflineIf(boolean wasLive, String applicationId, Mono<? extends Artifact> imported) {
        return imported.flatMap(artifact -> wasLive
                ? publishApproval.markNotLive(applicationId).thenReturn((Artifact) artifact)
                : Mono.just((Artifact) artifact));
    }
}

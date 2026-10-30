package com.appsmith.server.projectsrepo;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.constants.ArtifactType;
import com.appsmith.server.constants.SerialiseArtifactObjective;
import com.appsmith.server.domains.Application;
import com.appsmith.server.dtos.ApplicationImportDTO;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.exports.internal.ExportService;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.services.ArtifactCopyAudit;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fork: moves apps between the instance and the projects repo (tools/cab-sync, through the MCP tools export_application
 * and import_application).
 *
 * <p>The export is the edit copy, read with the edit permission (as Appsmith's own git export does), so a DEV can pull
 * the apps they build; the file export ({@code /api/v1/applications/export}) needs the export permission, which only
 * ADMIN has. It never carries the published copy or datasource details (see {@link EditCopyJson}).
 *
 * <p>The import is the normal app import: a new app, or the edit copy of an existing one (the published copy and its
 * live state stay as they are, see {@code ImportServiceImpl}).
 */
@Service
public class ProjectsRepoService {

    private final ExportService exportService;
    private final ImportService importService;
    private final ApplicationRepository applicationRepository;
    private final Gson gson;

    public ProjectsRepoService(
            ExportService exportService,
            ImportService importService,
            ApplicationRepository applicationRepository,
            Gson gson) {
        this.exportService = exportService;
        this.importService = importService;
        this.applicationRepository = applicationRepository;
        this.gson = gson;
    }

    /** The app's edit copy as an app JSON tree, as the import reads it, its secret values stripped. */
    public Mono<Object> exportEditCopy(String applicationId) {
        return exportTree(applicationId, true);
    }

    private Mono<Object> exportTree(String applicationId, boolean stripSecretValues) {
        if (!StringUtils.hasText(applicationId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "applicationId"));
        }
        return exportService
                .exportByArtifactId(applicationId, SerialiseArtifactObjective.VERSION_CONTROL, ArtifactType.APPLICATION)
                .map(exported -> {
                    ApplicationJson json = (ApplicationJson) exported;
                    EditCopyJson.dropPublishedSide(json);
                    // Through Gson, as the file export does, so the tree has the field names the import reads.
                    return EditCopyJson.withoutDatasourceDetails(
                            gson.fromJson(gson.toJson(json), Object.class), stripSecretValues);
                });
    }

    /**
     * Imports an app JSON as a new app, or over the edit copy of {@code applicationId}. The secret values the export
     * stripped are put back from the stored edit copy first ({@link EditCopyJson#putBackStoredValues}); one with no
     * stored value refuses the import. An app JSON identical to the app's current edit copy is then not imported: an
     * import always writes new layout ids, which would count as an edit and send an approved app back to draft.
     */
    public Mono<ImportResult> importEditCopy(String workspaceId, String applicationId, String appJson) {
        if (!StringUtils.hasText(appJson)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "app JSON"));
        }
        Object incoming;
        try {
            incoming = gson.fromJson(appJson, Object.class);
        } catch (RuntimeException e) {
            // Not JSON: the import reports it.
            return importJson(workspaceId, applicationId, appJson);
        }
        if (!StringUtils.hasText(applicationId)) {
            return Mono.fromCallable(() -> EditCopyJson.putBackStoredValues(incoming, null))
                    .flatMap(putBack -> importJson(workspaceId, applicationId, appJson));
        }
        // Any doubt about the stored edit copy (none readable) counts as a change: import it.
        return exportTree(applicationId, false)
                .map(Optional::of)
                .onErrorReturn(Optional.empty())
                .defaultIfEmpty(Optional.empty())
                .flatMap(current -> {
                    boolean putBack = EditCopyJson.putBackStoredValues(incoming, current.orElse(null));
                    String restoredJson = putBack ? gson.toJson(incoming) : appJson;
                    // Map equality ignores key order; list order is content.
                    boolean unchanged = current.isPresent()
                            && current.get()
                                    .equals(EditCopyJson.withoutDatasourceDetails(
                                            gson.fromJson(restoredJson, Object.class), false));
                    return unchanged
                            ? applicationRepository
                                    .findById(applicationId)
                                    .map(application -> new ImportResult(
                                            application.getId(),
                                            application.getWorkspaceId(),
                                            application.getName(),
                                            List.of(),
                                            true))
                            : importJson(workspaceId, applicationId, restoredJson);
                });
    }

    private Mono<ImportResult> importJson(String workspaceId, String applicationId, String appJson) {
        return ArtifactCopyAudit.suppress(
                        importService.extractArtifactExchangeJsonAndSaveArtifact(appJson, workspaceId, applicationId))
                .map(imported -> {
                    ApplicationImportDTO dto = (ApplicationImportDTO) imported;
                    Application application = dto.getApplication();
                    List<String> unconfigured = dto.getUnConfiguredDatasourceList() == null
                            ? List.of()
                            : dto.getUnConfiguredDatasourceList().stream()
                                    .map(Datasource::getName)
                                    .filter(Objects::nonNull)
                                    .sorted()
                                    .toList();
                    return new ImportResult(
                            application.getId(),
                            application.getWorkspaceId(),
                            application.getName(),
                            unconfigured,
                            false);
                });
    }

    /**
     * What an import answers: the app it wrote, the datasources it uses that still need a configuration, and whether
     * nothing was imported because the file was the app's edit copy already.
     */
    public record ImportResult(
            String applicationId,
            String workspaceId,
            String name,
            List<String> unconfiguredDatasources,
            boolean unchanged) {}
}

package com.appsmith.server.projectsrepo;

import com.appsmith.external.models.Datasource;
import com.appsmith.server.catalog.ApplicationCatalogService;
import com.appsmith.server.catalog.ApplicationTags;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@Slf4j
@Service
public class ProjectsRepoService {

    private static final String EXPORTED_APPLICATION = "exportedApplication";

    private final ExportService exportService;
    private final ImportService importService;
    private final ApplicationRepository applicationRepository;
    private final ApplicationCatalogService applicationCatalogService;
    private final Gson gson;

    public ProjectsRepoService(
            ExportService exportService,
            ImportService importService,
            ApplicationRepository applicationRepository,
            ApplicationCatalogService applicationCatalogService,
            Gson gson) {
        this.exportService = exportService;
        this.importService = importService;
        this.applicationRepository = applicationRepository;
        this.applicationCatalogService = applicationCatalogService;
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
                .onErrorResume(error -> {
                    // Only the error's type and message: never the stored edit copy, which holds secret values.
                    log.warn(
                            "Projects repo import over {}: the stored edit copy could not be read ({}: {}), importing"
                                    + " without it",
                            applicationId,
                            error.getClass().getSimpleName(),
                            error.getMessage());
                    return Mono.just(Optional.empty());
                })
                .defaultIfEmpty(Optional.empty())
                .flatMap(current -> {
                    boolean putBack = EditCopyJson.putBackStoredValues(incoming, current.orElse(null));
                    String restoredJson = putBack ? gson.toJson(incoming) : appJson;
                    Object restored =
                            EditCopyJson.withoutDatasourceDetails(gson.fromJson(restoredJson, Object.class), false);
                    // Map equality ignores key order; list order is content.
                    boolean onlyTagsMayDiffer =
                            current.isPresent() && withoutTags(current.get()).equals(withoutTags(restored));
                    if (!onlyTagsMayDiffer) {
                        return importJson(workspaceId, applicationId, restoredJson);
                    }
                    // Fork: a change of the tags alone is written as tags, not imported: an import writes new layout
                    // ids, which would cancel a pending release request. No tags and an empty list are the same.
                    List<String> tags = tagsOf(restored);
                    if (ApplicationTags.same(tagsOf(current.get()), tags)) {
                        return notImportedResult(applicationId, true);
                    }
                    return applicationCatalogService
                            .setImportedTags(applicationId, tags)
                            .then(Mono.defer(() -> notImportedResult(applicationId, false)));
                });
    }

    // Nothing was imported: the file was the edit copy already (unchanged), or only its tags were new (tagsOnly).
    private Mono<ImportResult> notImportedResult(String applicationId, boolean unchanged) {
        return applicationRepository
                .findById(applicationId)
                .map(application -> new ImportResult(
                        application.getId(),
                        application.getWorkspaceId(),
                        application.getName(),
                        List.of(),
                        unchanged,
                        !unchanged));
    }

    // The app JSON tree without exportedApplication.tags, for comparing everything else.
    private static Object withoutTags(Object tree) {
        if (!(tree instanceof Map<?, ?> map) || !(map.get(EXPORTED_APPLICATION) instanceof Map<?, ?> exported)) {
            return tree;
        }
        Map<Object, Object> application = new LinkedHashMap<>(exported);
        application.remove(Application.Fields.tags);
        Map<Object, Object> copy = new LinkedHashMap<>(map);
        copy.put(EXPORTED_APPLICATION, application);
        return copy;
    }

    private static List<String> tagsOf(Object tree) {
        if (!(tree instanceof Map<?, ?> map)
                || !(map.get(EXPORTED_APPLICATION) instanceof Map<?, ?> exported)
                || !(exported.get(Application.Fields.tags) instanceof List<?> tags)) {
            return List.of();
        }
        return tags.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
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
                            false,
                            false);
                });
    }

    /**
     * What an import answers: the app it wrote, the datasources it uses that still need a configuration, whether
     * nothing was imported because the file was the app's edit copy already, and whether only its tags were written
     * (the file differed from the edit copy in its tags alone, so the approval state is kept).
     */
    public record ImportResult(
            String applicationId,
            String workspaceId,
            String name,
            List<String> unconfiguredDatasources,
            boolean unchanged,
            boolean tagsOnly) {}
}

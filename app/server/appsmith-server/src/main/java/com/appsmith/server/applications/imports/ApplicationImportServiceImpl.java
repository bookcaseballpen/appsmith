package com.appsmith.server.applications.imports;

import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.Artifact;
import com.appsmith.server.domains.CustomJSLib;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.ApplicationImportDTO;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.ImportingMetaDTO;
import com.appsmith.server.dtos.MappedImportableResourcesDTO;
import com.appsmith.server.imports.importable.ImportableService;
import com.appsmith.server.imports.internal.artifactbased.ArtifactBasedImportService;
import com.appsmith.server.layouts.UpdateLayoutService;
import com.appsmith.server.migrations.JsonSchemaMigration;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.solutions.ActionPermission;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.DatasourcePermission;
import com.appsmith.server.solutions.PagePermission;
import com.appsmith.server.solutions.WorkspacePermission;
import com.appsmith.server.staticurl.StaticUrlService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ApplicationImportServiceImpl extends ApplicationImportServiceCEImpl
        implements ArtifactBasedImportService<Application, ApplicationImportDTO, ApplicationJson> {

    public ApplicationImportServiceImpl(
            ApplicationService applicationService,
            StaticUrlService staticUrlService,
            ApplicationPageService applicationPageService,
            NewActionService newActionService,
            UpdateLayoutService updateLayoutService,
            DatasourcePermission datasourcePermission,
            WorkspacePermission workspacePermission,
            ApplicationPermission applicationPermission,
            PagePermission pagePermission,
            ActionPermission actionPermission,
            JsonSchemaMigration jsonSchemaMigration,
            ImportableService<Theme> themeImportableService,
            ImportableService<NewPage> newPageImportableService,
            ImportableService<CustomJSLib> customJSLibImportableService,
            ImportableService<NewAction> newActionImportableService,
            ImportableService<ActionCollection> actionCollectionImportableService) {
        super(
                applicationService,
                staticUrlService,
                applicationPageService,
                newActionService,
                updateLayoutService,
                datasourcePermission,
                workspacePermission,
                applicationPermission,
                pagePermission,
                actionPermission,
                jsonSchemaMigration,
                themeImportableService,
                newPageImportableService,
                customJSLibImportableService,
                newActionImportableService,
                actionCollectionImportableService);
    }

    /**
     * Fork: Gson writes the file's tags straight into the field, past {@link Application#setTags}; route them through
     * it so an imported app's tags are normalised and clamped like every other write.
     */
    @Override
    public Mono<Application> updateAndSaveArtifactInContext(
            Artifact artifactFromJson,
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Mono<User> currentUserMono) {
        Application application = (Application) artifactFromJson;
        application.setTags(application.getTags());
        return super.updateAndSaveArtifactInContext(
                artifactFromJson, importingMetaDTO, mappedImportableResourcesDTO, currentUserMono);
    }
}

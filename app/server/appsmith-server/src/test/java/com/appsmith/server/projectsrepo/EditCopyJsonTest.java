package com.appsmith.server.projectsrepo;

import com.appsmith.external.models.ActionDTO;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.Theme;
import com.appsmith.server.dtos.ActionCollectionDTO;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.PageDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EditCopyJsonTest {

    @Test
    void should_keepOnlyEditCopy_when_publishedSideIsDropped() {
        // Given
        ApplicationJson json = new ApplicationJson();
        Application application = new Application();
        application.setPublishedAppLayout(new Application.AppLayout(Application.AppLayout.Type.MOBILE));
        application.setUnpublishedAppLayout(new Application.AppLayout(Application.AppLayout.Type.DESKTOP));
        application.setLastDeployedAt(Instant.now());
        application.setPublishedModeThemeId("theme");
        json.setExportedApplication(application);
        json.setPublishedTheme(new Theme());
        json.setPublishedDefaultPageName("Page1");
        NewPage page = new NewPage();
        page.setUnpublishedPage(new PageDTO());
        page.setPublishedPage(new PageDTO());
        json.setPageList(new ArrayList<>(List.of(page)));
        NewAction action = new NewAction();
        action.setUnpublishedAction(new ActionDTO());
        action.setPublishedAction(new ActionDTO());
        json.setActionList(new ArrayList<>(List.of(action)));
        ActionCollection collection = new ActionCollection();
        collection.setUnpublishedCollection(new ActionCollectionDTO());
        collection.setPublishedCollection(new ActionCollectionDTO());
        json.setActionCollectionList(new ArrayList<>(List.of(collection)));

        // When
        EditCopyJson.dropPublishedSide(json);

        // Then
        assertThat(application.getPublishedAppLayout()).isNull();
        assertThat(application.getLastDeployedAt()).isNull();
        assertThat(application.getPublishedModeThemeId()).isNull();
        assertThat(application.getUnpublishedAppLayout().getType()).isEqualTo(Application.AppLayout.Type.DESKTOP);
        assertThat(json.getPublishedTheme()).isNull();
        assertThat(json.getPublishedDefaultPageName()).isNull();
        assertThat(page.getPublishedPage()).isNull();
        assertThat(page.getUnpublishedPage()).isNotNull();
        assertThat(action.getPublishedAction()).isNull();
        assertThat(action.getUnpublishedAction()).isNotNull();
        assertThat(collection.getPublishedCollection()).isNull();
        assertThat(collection.getUnpublishedCollection()).isNotNull();
    }

    @Test
    void should_keepOnlyNameAndPlugin_when_exportHasDatasourceDetails() {
        // Given
        Map<String, Object> exported = new HashMap<>();
        exported.put(
                "datasourceList",
                List.of(Map.of(
                        "name", "Orders DB",
                        "pluginId", "postgres-plugin",
                        "gitSyncId", "sync1",
                        "datasourceConfiguration", Map.of("url", "db.internal:5432", "authentication", Map.of()))));
        exported.put("decryptedFields", Map.of("Orders DB", Map.of("password", "secret")));
        exported.put("datasourceConfigurationStructureList", List.of(Map.of("tables", List.of())));
        exported.put("publishedTheme", Map.of());
        exported.put("pageList", List.of());

        // When
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) EditCopyJson.withoutDatasourceDetails(exported);

        // Then
        assertThat(result.get("datasourceList"))
                .isEqualTo(List.of(Map.of("name", "Orders DB", "pluginId", "postgres-plugin")));
        assertThat(result)
                .doesNotContainKeys("decryptedFields", "datasourceConfigurationStructureList", "publishedTheme")
                .containsKey("pageList");
    }

    @Test
    void should_dropSavedDatasourceConfigAndAnyAuthentication_when_queriesReferenceDatasources() {
        // Given
        Map<String, Object> savedReference = new HashMap<>(Map.of(
                "id", "Orders DB",
                "name", "Orders DB",
                "datasourceConfiguration", new HashMap<>(Map.of("url", "db.internal"))));
        Map<String, Object> embeddedConfiguration = new HashMap<>(Map.of(
                "url",
                "https://api.example.com",
                "authentication",
                Map.of("authenticationType", "basic", "password", "secret")));
        Map<String, Object> embedded = new HashMap<>(
                Map.of("name", "DEFAULT_REST_DATASOURCE", "datasourceConfiguration", embeddedConfiguration));
        Map<String, Object> exported = new HashMap<>(Map.of(
                "actionList",
                List.of(
                        Map.of("unpublishedAction", Map.of("datasource", savedReference)),
                        Map.of("unpublishedAction", Map.of("datasource", embedded)))));

        // When
        EditCopyJson.withoutDatasourceDetails(exported);

        // Then
        assertThat(savedReference).doesNotContainKey("datasourceConfiguration");
        assertThat(embeddedConfiguration)
                .containsEntry("url", "https://api.example.com")
                .doesNotContainKey("authentication");
    }
}

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
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditCopyJsonTest {

    private static final String STRIPPED = EditCopyJson.STRIPPED_VALUE;

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

    @Test
    void should_replaceLiteralSecretValues_when_apiQueryIsExported() {
        // Given
        Map<String, Object> exported = exportWithApiQuery("query1");

        // When
        EditCopyJson.withoutDatasourceDetails(exported);

        // Then
        Map<String, Object> dto = editCopy(exported);
        Map<String, Object> actionConfiguration = child(dto, "actionConfiguration");
        assertThat(actionConfiguration.get("headers"))
                .isEqualTo(List.of(
                        Map.of("key", "Authorization", "value", STRIPPED),
                        Map.of("key", "Content-Type", "value", "application/json"),
                        Map.of("key", "X-Api-Key", "value", "{{appsmith.store.apiKey}}"),
                        Map.of("key", "X-Session-Token", "value", "")));
        assertThat(actionConfiguration.get("queryParameters"))
                .isEqualTo(List.of(Map.of("key", "api_key", "value", STRIPPED), Map.of("key", "page", "value", "1")));
        assertThat(actionConfiguration.get("path"))
                .isEqualTo("/orders?token=" + STRIPPED + "&page=2&signature={{Api.sign('a&secret=b')}}");
        Map<String, Object> datasourceConfiguration = child(child(dto, "datasource"), "datasourceConfiguration");
        assertThat(datasourceConfiguration.get("url"))
                .isEqualTo("https://api.example.com/v1?client_secret=" + STRIPPED + "&region=eu");
        assertThat(datasourceConfiguration.get("headers"))
                .isEqualTo(List.of(Map.of("key", "Cookie", "value", STRIPPED)));
        assertThat(datasourceConfiguration.get("queryParameters"))
                .isEqualTo(List.of(Map.of("key", "Password", "value", STRIPPED)));
    }

    @ParameterizedTest
    @CsvSource({
        "Authorization, true",
        "Proxy-Authorization, true",
        "cookie, true",
        "X-API-KEY, true",
        "apikey, true",
        "x-access-token, true",
        "client_secret, true",
        "PASSWORD, true",
        "auth, true",
        "X-Signature, true",
        "Content-Type, false",
        "Accept, false",
        "author, false",
        "page, false"
    })
    void should_stripValueOnlyOfSecretNames_when_headerIsExported(String name, boolean secret) {
        // Given
        Map<String, Object> exported = exportWithHeader(name, "literal-value");

        // When
        EditCopyJson.withoutDatasourceDetails(exported);

        // Then
        assertThat(firstHeader(exported).get("value")).isEqualTo(secret ? STRIPPED : "literal-value");
    }

    @Test
    void should_keepSecretValues_when_secretStrippingIsOff() {
        // Given
        Map<String, Object> exported = exportWithApiQuery("query1");

        // When
        Object result = EditCopyJson.withoutDatasourceDetails(exported, false);

        // Then
        assertThat(result).isEqualTo(exportWithApiQuery("query1"));
    }

    @Test
    void should_putStoredValuesBack_when_pulledFileIsPushed() {
        // Given
        Map<String, Object> stored = exportWithApiQuery("query1");
        Object pushed = EditCopyJson.withoutDatasourceDetails(exportWithApiQuery("query1"));

        // When
        boolean putBack = EditCopyJson.putBackStoredValues(pushed, stored);

        // Then
        assertThat(putBack).isTrue();
        assertThat(pushed).isEqualTo(stored);
    }

    @Test
    void should_keepValuesWrittenInFile_when_pushedFileHasNoStrippedValues() {
        // Given
        Map<String, Object> stored = exportWithApiQuery("query1");
        Map<String, Object> pushed = exportWithHeader("Authorization", "Bearer new-token");

        // When
        boolean putBack = EditCopyJson.putBackStoredValues(pushed, stored);

        // Then
        assertThat(putBack).isFalse();
        assertThat(firstHeader(pushed).get("value")).isEqualTo("Bearer new-token");
    }

    @Test
    void should_refusePush_when_strippedValueHasNoStoredValue() {
        // Given
        Map<String, Object> stored = exportWithApiQuery("query1");
        Object pushed = EditCopyJson.withoutDatasourceDetails(exportWithApiQuery("copied-query"));

        // When / Then
        assertThatThrownBy(() -> EditCopyJson.putBackStoredValues(pushed, stored))
                .isInstanceOf(AppsmithException.class)
                .satisfies(error ->
                        assertThat(((AppsmithException) error).getError()).isEqualTo(AppsmithError.GENERIC_BAD_REQUEST))
                .hasMessageContaining("query 'GetOrders' headers 'Authorization'")
                .hasMessageContaining("query 'GetOrders' URL parameter 'token'")
                .hasMessageContaining("query 'GetOrders' URL parameter 'client_secret'");
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.example.com, https://api.example.com, /orders/all",
        "https://api.example.com, https://API.Example.COM, /orders",
        "https://{{Env.host}}, https://{{Env.host}}, /orders"
    })
    void should_putStoredValuesBack_when_queryTargetIsUnchanged(String storedOrigin, String pushedOrigin, String path) {
        // Given
        Map<String, Object> stored = withOrigin(exportWithApiQuery("query1"), storedOrigin, "/orders");
        Object pushed =
                withOrigin(EditCopyJson.withoutDatasourceDetails(exportWithApiQuery("query1")), pushedOrigin, path);

        // When
        boolean putBack = EditCopyJson.putBackStoredValues(pushed, stored);

        // Then
        assertThat(putBack).isTrue();
        assertThat(firstHeader(pushed).get("value")).isEqualTo("Bearer s3cret");
        assertThat(child(child(editCopy(pushed), "datasource"), "datasourceConfiguration")
                        .get("headers"))
                .isEqualTo(List.of(Map.of("key", "Cookie", "value", "session=abc")));
        assertThat(child(editCopy(pushed), "actionConfiguration").get("path"))
                .asString()
                .contains("token=t-456");
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.example.com, https://evil.example.net",
        "https://api.example.com, https://api.example.com.evil.net",
        "https://api.example.com, https://api.example.com:8443",
        "https://api.example.com, http://api.example.com",
        "https://api.example.com, https://user@api.example.com",
        "https://api.example.com, https://{{Env.host}}",
        "https://{{Env.host}}, https://{{Env.otherHost}}"
    })
    void should_refusePush_when_queryUrlPointsElsewhere(String storedOrigin, String pushedOrigin) {
        // Given
        Map<String, Object> stored = withOrigin(exportWithApiQuery("query1"), storedOrigin, "/orders");
        Object pushed = withOrigin(
                EditCopyJson.withoutDatasourceDetails(exportWithApiQuery("query1")), pushedOrigin, "/orders");

        // When / Then
        assertThatThrownBy(() -> EditCopyJson.putBackStoredValues(pushed, stored))
                .isInstanceOf(AppsmithException.class)
                .satisfies(error ->
                        assertThat(((AppsmithException) error).getError()).isEqualTo(AppsmithError.GENERIC_BAD_REQUEST))
                .hasMessageContaining("another scheme, host, port or datasource")
                .hasMessageContaining("query 'GetOrders' headers 'Authorization'")
                .hasMessageContaining("query 'GetOrders' queryParameters 'api_key'")
                .hasMessageContaining("query 'GetOrders' headers 'Cookie'")
                .hasMessageContaining("query 'GetOrders' URL parameter 'token'")
                .hasMessageContaining("query 'GetOrders' URL parameter 'client_secret'")
                .hasMessageNotContaining("s3cret")
                .hasMessageNotContaining("no stored value");
        assertThat(firstHeader(pushed).get("value")).isEqualTo(STRIPPED);
    }

    @Test
    void should_putStoredValueBack_when_savedDatasourceQueryOnlyChangesPath() {
        // Given
        Map<String, Object> stored = exportWithSavedDatasource("Orders API", "/orders");
        Object pushed =
                EditCopyJson.withoutDatasourceDetails(exportWithSavedDatasource("Orders API", "/orders/{{page}}"));

        // When
        boolean putBack = EditCopyJson.putBackStoredValues(pushed, stored);

        // Then
        assertThat(putBack).isTrue();
        assertThat(firstHeader(pushed).get("value")).isEqualTo("Bearer s3cret");
    }

    @ParameterizedTest
    @CsvSource({
        "Other API, /orders",
        "Orders API, .evil.net/orders",
        "Orders API, @evil.net/orders",
        "Orders API, :8443/orders",
        "Orders API, https://evil.net/orders",
        "Orders API, {{Env.host}}/orders"
    })
    void should_refusePush_when_savedDatasourceQueryPointsElsewhere(String datasourceId, String path) {
        // Given
        Map<String, Object> stored = exportWithSavedDatasource("Orders API", "/orders");
        Object pushed = EditCopyJson.withoutDatasourceDetails(exportWithSavedDatasource(datasourceId, path));

        // When / Then
        assertThatThrownBy(() -> EditCopyJson.putBackStoredValues(pushed, stored))
                .isInstanceOf(AppsmithException.class)
                .hasMessageContaining("another scheme, host, port or datasource")
                .hasMessageContaining("query 'GetOrders' headers 'Authorization'");
        assertThat(firstHeader(pushed).get("value")).isEqualTo(STRIPPED);
    }

    /** The export with its query's embedded datasource URL on {@code origin} and the query's path starting {@code path}. */
    private static <T> T withOrigin(T exported, String origin, String path) {
        Map<String, Object> dto = editCopy(exported);
        child(child(dto, "datasource"), "datasourceConfiguration")
                .put("url", origin + "/v1?client_secret=" + valueOf(dto, "client_secret") + "&region=eu");
        Map<String, Object> actionConfiguration = child(dto, "actionConfiguration");
        String storedPath = (String) actionConfiguration.get("path");
        actionConfiguration.put("path", path + storedPath.substring(storedPath.indexOf('?')));
        return exported;
    }

    /** The value of the URL parameter {@code name} in the query's embedded datasource URL. */
    private static String valueOf(Map<String, Object> dto, String name) {
        String url = (String)
                child(child(dto, "datasource"), "datasourceConfiguration").get("url");
        String rest = url.substring(url.indexOf(name + "=") + name.length() + 1);
        return rest.contains("&") ? rest.substring(0, rest.indexOf('&')) : rest;
    }

    /** An exported app with one API query on the saved datasource {@code datasourceId}, with a secret header. */
    private static Map<String, Object> exportWithSavedDatasource(String datasourceId, String path) {
        Map<String, Object> actionConfiguration = new HashMap<>();
        actionConfiguration.put("path", path);
        actionConfiguration.put("headers", List.of(property("Authorization", "Bearer s3cret")));
        Map<String, Object> exported = exportWith("query1", actionConfiguration, new HashMap<>());
        Map<String, Object> datasource = child(editCopy(exported), "datasource");
        datasource.put("id", datasourceId);
        datasource.put("name", datasourceId);
        return exported;
    }

    /** An exported app with one API query that has its own URL and secrets in its headers, parameters and URLs. */
    private static Map<String, Object> exportWithApiQuery(String gitSyncId) {
        Map<String, Object> actionConfiguration = new HashMap<>();
        actionConfiguration.put("path", "/orders?token=t-456&page=2&signature={{Api.sign('a&secret=b')}}");
        actionConfiguration.put(
                "headers",
                List.of(
                        property("Authorization", "Bearer s3cret"),
                        property("Content-Type", "application/json"),
                        property("X-Api-Key", "{{appsmith.store.apiKey}}"),
                        property("X-Session-Token", "")));
        actionConfiguration.put("queryParameters", List.of(property("api_key", "k-123"), property("page", "1")));
        Map<String, Object> datasourceConfiguration = new HashMap<>();
        datasourceConfiguration.put("url", "https://api.example.com/v1?client_secret=cs-789&region=eu");
        datasourceConfiguration.put("headers", List.of(property("Cookie", "session=abc")));
        datasourceConfiguration.put("queryParameters", List.of(property("Password", "pw")));
        return exportWith(gitSyncId, actionConfiguration, datasourceConfiguration);
    }

    private static Map<String, Object> exportWithHeader(String name, String value) {
        Map<String, Object> actionConfiguration = new HashMap<>();
        actionConfiguration.put("headers", List.of(property(name, value)));
        return exportWith("query1", actionConfiguration, new HashMap<>());
    }

    private static Map<String, Object> exportWith(
            String gitSyncId, Map<String, Object> actionConfiguration, Map<String, Object> datasourceConfiguration) {
        Map<String, Object> datasource = new HashMap<>();
        datasource.put("name", "DEFAULT_REST_DATASOURCE");
        datasource.put("datasourceConfiguration", datasourceConfiguration);
        Map<String, Object> dto = new HashMap<>();
        dto.put("name", "GetOrders");
        dto.put("actionConfiguration", actionConfiguration);
        dto.put("datasource", datasource);
        Map<String, Object> exported = new HashMap<>();
        exported.put("actionList", List.of(Map.of("gitSyncId", gitSyncId, "unpublishedAction", dto)));
        return exported;
    }

    private static Map<String, Object> property(String key, String value) {
        return new HashMap<>(Map.of("key", key, "value", value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> editCopy(Object exported) {
        List<Map<String, Object>> actions =
                (List<Map<String, Object>>) ((Map<String, Object>) exported).get("actionList");
        return child(actions.get(0), "unpublishedAction");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstHeader(Object exported) {
        return ((List<Map<String, Object>>)
                        child(editCopy(exported), "actionConfiguration").get("headers"))
                .get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> node, String key) {
        return (Map<String, Object>) node.get(key);
    }
}

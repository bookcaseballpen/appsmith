package com.appsmith.server.projectsrepo;

import com.appsmith.server.domains.Application;
import com.appsmith.server.dtos.ApplicationJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fork: the app JSON the projects repo works with is the edit copy only, without datasource details.
 *
 * <p>{@link #dropPublishedSide} clears what only a publish may write; the import over an existing app applies it to every
 * file (see {@code ImportServiceImpl}). {@link #withoutDatasourceDetails} keeps only the name and plugin of each
 * datasource, so no host, URL, credential or other secret ever leaves the instance through the projects repo.
 */
public final class EditCopyJson {

    // Top-level keys of an exported file that carry the published copy, datasource details or server bookkeeping.
    private static final Set<String> DROPPED_TOP_LEVEL_KEYS = Set.of(
            "decryptedFields",
            "datasourceConfigurationStructureList",
            "modifiedResources",
            "publishedTheme",
            "publishedPageOrder",
            "publishedDefaultPageName",
            "publishedLayoutmongoEscapedWidgets");

    private static final List<String> DATASOURCE_KEPT_KEYS = List.of("name", "pluginId");

    private EditCopyJson() {}

    /**
     * Clears everything in the file that only a publish may write. The importers then leave the stored published copy
     * as it is: pages never take the file's published page, and queries, JS objects and the app copy only the file's
     * non-null fields over the stored ones.
     */
    public static void dropPublishedSide(ApplicationJson json) {
        Application application = json.getExportedApplication();
        if (application != null) {
            application.setPublishedPages(null);
            application.setPublishedApplicationDetail(null);
            application.setPublishedAppLayout(null);
            application.setPublishedCustomJSLibs(null);
            application.setPublishedModeThemeId(null);
            application.setLastDeployedAt(null);
        }
        json.setPublishedTheme(null);
        json.setPublishedPageOrder(null);
        json.setPublishedDefaultPageName(null);
        json.setPublishedLayoutmongoEscapedWidgets(null);
        nonNull(json.getPageList()).forEach(page -> page.setPublishedPage(null));
        nonNull(json.getActionList()).forEach(action -> action.setPublishedAction(null));
        nonNull(json.getActionCollectionList()).forEach(collection -> collection.setPublishedCollection(null));
    }

    /**
     * The exported file (as a JSON tree) with each datasource reduced to its name and plugin, no stored structure or
     * decrypted fields, and no datasource configuration on the queries that use a saved datasource. A query with its
     * own URL (an API query without a saved datasource) keeps that URL, its headers and parameters, which are part of
     * the query, but never its authentication.
     */
    @SuppressWarnings("unchecked")
    public static Object withoutDatasourceDetails(Object exported) {
        if (!(exported instanceof Map<?, ?> tree)) {
            return exported;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) tree);
        DROPPED_TOP_LEVEL_KEYS.forEach(result::remove);

        if (result.get("datasourceList") instanceof List<?> datasources) {
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Object datasource : datasources) {
                if (datasource instanceof Map<?, ?> fields) {
                    Map<String, Object> reduced = new LinkedHashMap<>();
                    DATASOURCE_KEPT_KEYS.forEach(key -> {
                        if (fields.get(key) != null) {
                            reduced.put(key, fields.get(key));
                        }
                    });
                    kept.add(reduced);
                }
            }
            result.put("datasourceList", kept);
        }

        if (result.get("actionList") instanceof List<?> actions) {
            for (Object action : actions) {
                if (action instanceof Map<?, ?> fields) {
                    for (String side : List.of("unpublishedAction", "publishedAction")) {
                        if (fields.get(side) instanceof Map<?, ?> dto
                                && dto.get("datasource") instanceof Map<?, ?> datasource) {
                            stripDatasourceReference((Map<String, Object>) datasource);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void stripDatasourceReference(Map<String, Object> datasource) {
        datasource.remove("decryptedFields");
        datasource.remove("datasourceStorages");
        if (!(datasource.get("datasourceConfiguration") instanceof Map<?, ?> configuration)) {
            return;
        }
        // A saved datasource is referenced by name; only an embedded one (no saved datasource) keeps its configuration.
        boolean savedDatasource = datasource.get("id") instanceof String id && !id.isBlank();
        if (savedDatasource) {
            datasource.remove("datasourceConfiguration");
        } else {
            configuration.remove("authentication");
            configuration.remove("sshProxy");
            configuration.remove("sshProxyEnabled");
        }
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}

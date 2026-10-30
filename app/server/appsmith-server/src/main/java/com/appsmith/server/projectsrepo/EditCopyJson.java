package com.appsmith.server.projectsrepo;

import com.appsmith.server.domains.Application;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fork: the app JSON the projects repo works with is the edit copy only, without datasource details.
 *
 * <p>{@link #dropPublishedSide} clears what only a publish may write; the import over an existing app applies it to every
 * file (see {@code ImportServiceImpl}). {@link #withoutDatasourceDetails} keeps only the name and plugin of each
 * datasource, and replaces the literal values of a query's secret headers and parameters by {@link #STRIPPED_VALUE},
 * so no host, URL, credential or other secret ever leaves the instance through the projects repo.
 * {@link #putBackStoredValues} puts the stored values back when such a file is pushed.
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

    /** What an exported file holds in place of a secret header or parameter value; a push puts the stored one back. */
    public static final String STRIPPED_VALUE = "__secret_kept_on_server__";

    // Header and parameter names whose literal values are secrets: these names exactly, or any name containing a part.
    private static final Set<String> SECRET_NAMES = Set.of("auth");
    private static final List<String> SECRET_NAME_PARTS = List.of(
            "authorization", "cookie", "token", "secret", "key", "password", "passwd", "signature", "credential");

    // Where a query (its action DTO) holds headers and parameters, as name/value lists and as URLs.
    private static final List<List<String>> SECRET_PROPERTY_LISTS = List.of(
            List.of("actionConfiguration", "headers"),
            List.of("actionConfiguration", "queryParameters"),
            List.of("datasource", "datasourceConfiguration", "headers"),
            List.of("datasource", "datasourceConfiguration", "queryParameters"));
    private static final List<List<String>> SECRET_URLS =
            List.of(List.of("actionConfiguration", "path"), List.of("datasource", "datasourceConfiguration", "url"));

    private static final Pattern BINDING = Pattern.compile("\\{\\{.*?}}", Pattern.DOTALL);
    private static final Pattern URL_PARAMETER = Pattern.compile("([?&])([^=&#?]+)=([^&#]*)");

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
     * the query, but never its authentication, and the literal values of its secret headers and parameters become
     * {@link #STRIPPED_VALUE} (see {@link #putBackStoredValues}).
     */
    public static Object withoutDatasourceDetails(Object exported) {
        return withoutDatasourceDetails(exported, true);
    }

    /**
     * As {@link #withoutDatasourceDetails(Object)}; with {@code stripSecretValues} false the secret header and
     * parameter values stay, to compare a pushed file (its stripped values put back) with the stored edit copy.
     */
    @SuppressWarnings("unchecked")
    public static Object withoutDatasourceDetails(Object exported, boolean stripSecretValues) {
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
                        if (!(fields.get(side) instanceof Map<?, ?> dto)) {
                            continue;
                        }
                        if (dto.get("datasource") instanceof Map<?, ?> datasource) {
                            stripDatasourceReference((Map<String, Object>) datasource);
                        }
                        if (stripSecretValues) {
                            stripSecretValues((Map<String, Object>) dto);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Puts the stored value back for every {@link #STRIPPED_VALUE} in the edit copy of a pushed file (a JSON tree,
     * changed in place), from {@code current}, the app's stored edit copy as {@code withoutDatasourceDetails(..,
     * false)} gives it ({@code null} for a new app). Queries are matched by {@code gitSyncId}, headers and parameters
     * by name (ignoring case) and position among those of that name. Without this, the import would store the marker
     * itself: it replaces a query's header and parameter lists and URL whole.
     *
     * @return whether any value was put back
     * @throws AppsmithException when a stripped value has no stored value (a new app or query, or a renamed header)
     */
    public static boolean putBackStoredValues(Object incoming, Object current) {
        Map<String, Map<String, Object>> storedQueries = new LinkedHashMap<>();
        for (Map<String, Object> action : actions(current)) {
            if (action.get("gitSyncId") instanceof String gitSyncId && editCopy(action) != null) {
                storedQueries.put(gitSyncId, editCopy(action));
            }
        }
        List<String> missing = new ArrayList<>();
        boolean putBack = false;
        for (Map<String, Object> action : actions(incoming)) {
            Map<String, Object> dto = editCopy(action);
            if (dto == null) {
                continue;
            }
            Map<String, Object> stored =
                    action.get("gitSyncId") instanceof String gitSyncId ? storedQueries.get(gitSyncId) : null;
            String query = "query '" + dto.get("name") + "'";
            for (List<String> path : SECRET_PROPERTY_LISTS) {
                if (!(valueAt(dto, path) instanceof List<?> properties)) {
                    continue;
                }
                List<?> storedProperties = valueAt(stored, path) instanceof List<?> list ? list : List.of();
                Map<String, Integer> seen = new LinkedHashMap<>();
                for (Object property : properties) {
                    if (!(property instanceof Map<?, ?> entry) || !(entry.get("key") instanceof String key)) {
                        continue;
                    }
                    int occurrence = seen.merge(key.toLowerCase(Locale.ROOT), 1, Integer::sum) - 1;
                    if (!STRIPPED_VALUE.equals(entry.get("value"))) {
                        continue;
                    }
                    Object value = storedPropertyValue(storedProperties, key, occurrence);
                    if (value == null) {
                        missing.add(query + " " + path.get(path.size() - 1) + " '" + key + "'");
                    } else {
                        putEntry(entry, "value", value);
                        putBack = true;
                    }
                }
            }
            for (List<String> path : SECRET_URLS) {
                if (!(valueAt(dto, path) instanceof String url) || !url.contains(STRIPPED_VALUE)) {
                    continue;
                }
                List<String[]> storedParameters =
                        valueAt(stored, path) instanceof String storedUrl ? urlParameters(storedUrl) : List.of();
                Map<String, Integer> seen = new LinkedHashMap<>();
                String restored = replaceUrlParameters(url, (key, value) -> {
                    int occurrence = seen.merge(key, 1, Integer::sum) - 1;
                    if (!STRIPPED_VALUE.equals(value)) {
                        return value;
                    }
                    String storedValue = storedParameters.stream()
                            .filter(parameter -> parameter[0].equals(key))
                            .skip(occurrence)
                            .map(parameter -> parameter[1])
                            .filter(candidate -> !STRIPPED_VALUE.equals(candidate))
                            .findFirst()
                            .orElse(null);
                    if (storedValue == null) {
                        missing.add(query + " URL parameter '" + key + "'");
                        return value;
                    }
                    return storedValue;
                });
                if (!restored.equals(url)) {
                    putEntry(parentAt(dto, path), path.get(path.size() - 1), restored);
                    putBack = true;
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new AppsmithException(
                    AppsmithError.GENERIC_BAD_REQUEST,
                    "these values are " + STRIPPED_VALUE + " (removed on export) and the app has no stored value to"
                            + " put back: " + String.join(", ", missing)
                            + ". Write a {{ }} binding in their place.");
        }
        return putBack;
    }

    /** Replaces the literal values of the secret headers and parameters of a query (its action DTO) by the marker. */
    @SuppressWarnings("unchecked")
    private static void stripSecretValues(Map<String, Object> dto) {
        for (List<String> path : SECRET_PROPERTY_LISTS) {
            if (valueAt(dto, path) instanceof List<?> properties) {
                for (Object property : properties) {
                    if (property instanceof Map<?, ?> entry
                            && entry.get("key") instanceof String key
                            && isSecretName(key)
                            && isLiteral(entry.get("value"))) {
                        ((Map<String, Object>) entry).put("value", STRIPPED_VALUE);
                    }
                }
            }
        }
        for (List<String> path : SECRET_URLS) {
            if (valueAt(dto, path) instanceof String url) {
                String stripped = replaceUrlParameters(
                        url, (key, value) -> isSecretName(key) && isLiteral(value) ? STRIPPED_VALUE : value);
                if (!stripped.equals(url)) {
                    putEntry(parentAt(dto, path), path.get(path.size() - 1), stripped);
                }
            }
        }
    }

    private static boolean isSecretName(String name) {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return SECRET_NAMES.contains(lower) || SECRET_NAME_PARTS.stream().anyMatch(lower::contains);
    }

    /** A value written in the query itself: not empty, no {@code {{ }}} binding, not stripped already. */
    private static boolean isLiteral(Object value) {
        return value instanceof String text && !text.isBlank() && !text.contains("{{") && !STRIPPED_VALUE.equals(text);
    }

    private static Object storedPropertyValue(List<?> storedProperties, String key, int occurrence) {
        return storedProperties.stream()
                .filter(property -> property instanceof Map<?, ?> entry
                        && entry.get("key") instanceof String storedKey
                        && storedKey.equalsIgnoreCase(key))
                .skip(occurrence)
                .map(property -> ((Map<?, ?>) property).get("value"))
                .filter(value -> value != null && !STRIPPED_VALUE.equals(value))
                .findFirst()
                .orElse(null);
    }

    /** The URL's query parameters (outside {@code {{ }}} bindings) as key and value, in order. */
    private static List<String[]> urlParameters(String url) {
        List<String[]> parameters = new ArrayList<>();
        replaceUrlParameters(url, (key, value) -> {
            parameters.add(new String[] {key, value});
            return value;
        });
        return parameters;
    }

    /** The URL with each query parameter's value (outside {@code {{ }}} bindings) replaced as {@code replace} says. */
    private static String replaceUrlParameters(String url, BiFunction<String, String, String> replace) {
        List<int[]> bindings = new ArrayList<>();
        Matcher binding = BINDING.matcher(url);
        while (binding.find()) {
            bindings.add(new int[] {binding.start(), binding.end()});
        }
        Matcher parameter = URL_PARAMETER.matcher(url);
        StringBuilder result = new StringBuilder();
        while (parameter.find()) {
            int start = parameter.start();
            int end = parameter.end();
            boolean inBinding = bindings.stream().anyMatch(span -> start < span[1] && end > span[0]);
            String value = inBinding ? parameter.group(3) : replace.apply(parameter.group(2), parameter.group(3));
            parameter.appendReplacement(
                    result, Matcher.quoteReplacement(parameter.group(1) + parameter.group(2) + "=" + value));
        }
        parameter.appendTail(result);
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actions(Object tree) {
        if (!(tree instanceof Map<?, ?> fields) || !(fields.get("actionList") instanceof List<?> actions)) {
            return List.of();
        }
        return actions.stream()
                .filter(action -> action instanceof Map<?, ?>)
                .map(action -> (Map<String, Object>) action)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> editCopy(Map<String, Object> action) {
        return action.get("unpublishedAction") instanceof Map<?, ?> dto ? (Map<String, Object>) dto : null;
    }

    private static Object valueAt(Map<String, Object> dto, List<String> path) {
        Map<?, ?> parent = parentAt(dto, path);
        return parent == null ? null : parent.get(path.get(path.size() - 1));
    }

    private static Map<?, ?> parentAt(Map<String, Object> dto, List<String> path) {
        Object node = dto;
        for (String key : path.subList(0, path.size() - 1)) {
            node = node instanceof Map<?, ?> map ? map.get(key) : null;
        }
        return node instanceof Map<?, ?> map ? map : null;
    }

    @SuppressWarnings("unchecked")
    private static void putEntry(Map<?, ?> map, String key, Object value) {
        ((Map<String, Object>) map).put(key, value);
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

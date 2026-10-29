package com.appsmith.server.repositories.postgres;

import com.appsmith.external.helpers.JsonForDatabase;
import com.appsmith.external.models.BaseDomain;
import com.appsmith.server.helpers.ce.bridge.BridgeQuery;
import com.appsmith.server.helpers.ce.bridge.BridgeUpdate;
import com.appsmith.server.helpers.ce.bridge.Check;
import com.appsmith.server.helpers.ce.bridge.Op;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fork: renders the database-neutral {@link BridgeQuery} and {@link BridgeUpdate} to PostgreSQL for one entity.
 * <p>
 * Nested keys ({@code a.b.c}) address JSON inside a jsonb column. Checks keep MongoDB's matching rules:
 * <ul>
 *   <li>a check on an array (at any level of the path) matches when any element matches, done with
 *   {@code jsonb_path_exists} in lax mode;</li>
 *   <li>negated checks ({@code notEqual}, {@code notIn}, {@code notExists}) also match rows without the field;</li>
 *   <li>{@code _id} in a key means the {@code id} field.</li>
 * </ul>
 * Values are always bound as parameters ({@code ?1}, {@code ?2}, ...); only field names, which come from code and are
 * checked against {@link #SAFE_KEY}, are written into the SQL text.
 */
public final class SqlRenderer {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final ObjectMapper JSON = JsonForDatabase.create();

    private final EntityMeta meta;

    private final List<Object> params = new ArrayList<>();

    public SqlRenderer(EntityMeta meta) {
        this.meta = meta;
    }

    public EntityMeta meta() {
        return meta;
    }

    public List<Object> params() {
        return params;
    }

    public String param(Object value) {
        params.add(value instanceof Enum<?> e ? e.name() : value);
        return "?" + params.size();
    }

    // ---- Keys ---------------------------------------------------------------------------------------------------

    private record KeyPath(String field, String column, boolean json, List<String> nested) {
        boolean isJsonish() {
            return json || !nested.isEmpty();
        }
    }

    private KeyPath resolve(String key) {
        final String[] parts = key.split("\\.");
        final List<String> names = new ArrayList<>(parts.length);
        for (String part : parts) {
            final String name = "_id".equals(part) ? "id" : part;
            if (!SAFE_KEY.matcher(name).matches()) {
                throw new IllegalArgumentException("Unsupported key '" + key + "' for " + meta.getType());
            }
            names.add(name);
        }
        final String field = names.get(0);
        final List<String> nested = names.subList(1, names.size());
        final boolean json = meta.isJson(field);
        if (!nested.isEmpty() && !json) {
            throw new IllegalArgumentException("Nested key '" + key + "' on a non-JSON field of " + meta.getType());
        }
        return new KeyPath(field, meta.column(field), json, List.copyOf(nested));
    }

    public String column(String field) {
        return resolve(field).column();
    }

    private static String textArray(List<String> path) {
        return "'{" + String.join(",", path) + "}'";
    }

    private static String jsonPathLiteral(List<String> nested) {
        final StringBuilder sb = new StringBuilder("$");
        for (String name : nested) {
            sb.append(".\"").append(name).append('"');
        }
        return sb.append("[*]").toString();
    }

    private static String jsonLiteral(Object value) {
        try {
            if (value instanceof Enum<?> e) {
                return JSON.writeValueAsString(e.name());
            }
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String toJson(Object value) {
        return jsonLiteral(value);
    }

    /** {@code jsonb_path_exists} over the value(s) at the path, with an optional filter on each value. */
    private String pathExists(KeyPath path, String filter) {
        String jsonPath = jsonPathLiteral(path.nested());
        if (filter != null) {
            jsonPath += " ? (" + filter + ")";
        }
        return "COALESCE(jsonb_path_exists(" + path.column() + ", CAST(" + param(jsonPath) + " AS jsonpath)), FALSE)";
    }

    private String nestedValue(KeyPath path) {
        return path.nested().isEmpty() ? path.column() : "(" + path.column() + " #> " + textArray(path.nested()) + ")";
    }

    private String nestedText(KeyPath path) {
        return path.nested().isEmpty()
                ? "(" + path.column() + " #>> '{}')"
                : "(" + path.column() + " #>> " + textArray(path.nested()) + ")";
    }

    // ---- WHERE --------------------------------------------------------------------------------------------------

    public <T extends BaseDomain> String where(List<BridgeQuery<T>> queries) {
        if (queries.isEmpty()) {
            return "TRUE";
        }
        return queries.stream().map(this::where).collect(Collectors.joining(" AND "));
    }

    public <T extends BaseDomain> String where(BridgeQuery<T> query) {
        final List<Check> checks = query.getChecks();
        if (checks.isEmpty()) {
            return "TRUE";
        }
        return "(" + checks.stream().map(this::check).collect(Collectors.joining(" AND ")) + ")";
    }

    @SuppressWarnings("unchecked")
    private String check(Check check) {
        if (check instanceof Check.Unit unit) {
            return unit(unit.op(), unit.key(), unit.value());
        }
        if (check instanceof Check.Or<?> or) {
            if (or.items().isEmpty()) {
                return "FALSE";
            }
            return "("
                    + ((List<BridgeQuery<BaseDomain>>) (List<?>) or.items())
                            .stream().map(this::where).collect(Collectors.joining(" OR "))
                    + ")";
        }
        if (check instanceof Check.And<?> and) {
            return "("
                    + ((List<BridgeQuery<BaseDomain>>) (List<?>) and.items())
                            .stream().map(this::where).collect(Collectors.joining(" AND "))
                    + ")";
        }
        if (check instanceof Check.Permission permission) {
            return permission(permission.permission(), permission.permissionGroups());
        }
        throw new IllegalArgumentException("Unknown check " + check);
    }

    /** {@code policyMap -> permission -> permissionGroups} holds any of the groups. */
    private String permission(String permission, Collection<String> permissionGroups) {
        if (permissionGroups.isEmpty()) {
            return "FALSE";
        }
        final String groupsArray = "{"
                + permissionGroups.stream()
                        .map(g -> "\"" + g.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        .collect(Collectors.joining(","))
                + "}";
        return "COALESCE(jsonb_exists_any(jsonb_extract_path(" + meta.column(BaseDomain.Fields.policyMap) + ", "
                + param(permission) + ", 'permissionGroups'), CAST(" + param(groupsArray) + " AS text[])), FALSE)";
    }

    private static List<?> asList(Object value) {
        if (value instanceof Collection<?> c) {
            return List.copyOf(c);
        }
        return List.of(value);
    }

    private String anyOf(List<?> values) {
        return values.stream().map(v -> "@ == " + jsonLiteral(v)).collect(Collectors.joining(" || "));
    }

    private String unit(Op op, String key, Object value) {
        final KeyPath path = resolve(key);
        final String col = path.column();
        final boolean jsonish = path.isJsonish();

        return switch (op) {
            case EQUAL -> jsonish ? pathExists(path, "@ == " + jsonLiteral(value)) : col + " = " + param(value);
            case NOT_EQUAL ->
                jsonish
                        ? "NOT " + pathExists(path, "@ == " + jsonLiteral(value))
                        : "(" + col + " IS NULL OR " + col + " <> " + param(value) + ")";
            case EQ_IGNORE_CASE -> "lower(" + (jsonish ? nestedText(path) : col) + ") = lower(" + param(value) + ")";
            case SEARCH_IGNORE_CASE -> {
                final String escaped = ((String) value).replaceAll("[\\\\_%]", "\\\\$0");
                yield col + " ILIKE " + param("%" + escaped + "%") + " ESCAPE '\\'";
            }
            case IN -> {
                final List<?> values = asList(value);
                if (values.isEmpty()) {
                    yield "FALSE";
                }
                if (jsonish) {
                    yield pathExists(path, anyOf(values));
                }
                yield col + " IN (" + values.stream().map(this::param).collect(Collectors.joining(", ")) + ")";
            }
            case NOT_IN -> {
                final List<?> values = asList(value);
                if (values.isEmpty()) {
                    yield "TRUE";
                }
                if (jsonish) {
                    yield "NOT " + pathExists(path, anyOf(values));
                }
                yield "(" + col + " IS NULL OR " + col + " NOT IN ("
                        + values.stream().map(this::param).collect(Collectors.joining(", ")) + "))";
            }
            case EXISTS -> path.nested().isEmpty() ? col + " IS NOT NULL" : pathExists(path, null);
            case NOT_EXISTS -> path.nested().isEmpty() ? col + " IS NULL" : "NOT " + pathExists(path, null);
            case IS_NULL ->
                path.nested().isEmpty()
                        ? col + " IS NULL"
                        : "(" + nestedValue(path) + " IS NULL OR " + nestedValue(path) + " = 'null'::jsonb)";
            case IS_NOT_NULL ->
                path.nested().isEmpty()
                        ? col + " IS NOT NULL"
                        : "(" + nestedValue(path) + " IS NOT NULL AND " + nestedValue(path) + " <> 'null'::jsonb)";
            case IS_TRUE -> jsonish ? pathExists(path, "@ == true") : col + " IS TRUE";
            case IS_FALSE -> jsonish ? pathExists(path, "@ == false") : col + " IS FALSE";
            case GREATER_THAN -> scalarCompare(path, ">", value);
            case GREATER_THAN_OR_EQUAL -> scalarCompare(path, ">=", value);
            case LESS_THAN -> scalarCompare(path, "<", value);
            case LESS_THAN_OR_EQUAL -> scalarCompare(path, "<=", value);
            case SIZE_LESS_THAN ->
                "(CASE WHEN jsonb_typeof(" + nestedValue(path) + ") = 'array' THEN jsonb_array_length("
                        + nestedValue(path) + ") ELSE 0 END) < " + param(value);
        };
    }

    private String scalarCompare(KeyPath path, String operator, Object value) {
        if (path.isJsonish()) {
            throw new UnsupportedOperationException("Range checks on JSON fields are not supported: " + path.field());
        }
        return path.column() + " " + operator + " " + param(value);
    }

    // ---- ORDER BY -----------------------------------------------------------------------------------------------

    public String orderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            final KeyPath path = resolve(order.getProperty());
            String expression = path.isJsonish() ? nestedText(path) : path.column();
            if (order.isIgnoreCase()) {
                expression = "lower(" + expression + ")";
            }
            // Mongo puts missing values first when ascending, and last when descending.
            parts.add(expression + (order.isAscending() ? " ASC NULLS FIRST" : " DESC NULLS LAST"));
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    // ---- UPDATE SET ---------------------------------------------------------------------------------------------

    public String set(BridgeUpdate update) {
        // Group the operations by column, so that several nested changes to one JSON column become one expression.
        final Map<String, String> assignments = new LinkedHashMap<>();
        for (BridgeUpdate.Operation operation : update.getOperations()) {
            final KeyPath path = resolve(operation.key());
            final String current = assignments.getOrDefault(path.column(), path.column());
            assignments.put(path.column(), apply(path, current, operation));
        }
        return assignments.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private String jsonParam(Object value) {
        return value == null ? "'null'::jsonb" : "CAST(" + param(jsonLiteral(value)) + " AS jsonb)";
    }

    private String apply(KeyPath path, String current, BridgeUpdate.Operation operation) {
        final Object value = operation.value();
        if (path.nested().isEmpty()) {
            return switch (operation.kind()) {
                case SET -> {
                    if (path.json()) {
                        yield value == null ? "NULL" : jsonParam(value);
                    }
                    yield value == null ? "NULL" : param(value);
                }
                case SET_FROM_FIELD -> resolve((String) value).column();
                case INC -> "COALESCE(" + current + ", 0) + 1";
                case PUSH, ADD_TO_SET, PULL ->
                    arrayChange("COALESCE(" + current + ", '[]'::jsonb)", operation.kind(), value);
            };
        }

        final String newValue =
                switch (operation.kind()) {
                    case SET -> jsonParam(value);
                    case INC ->
                        "to_jsonb(COALESCE((" + current + " #>> " + textArray(path.nested()) + ")::numeric, 0) + 1)";
                    case PUSH, ADD_TO_SET, PULL ->
                        arrayChange(
                                "COALESCE(" + current + " #> " + textArray(path.nested()) + ", '[]'::jsonb)",
                                operation.kind(),
                                value);
                    case SET_FROM_FIELD ->
                        throw new UnsupportedOperationException(
                                "setToValueFromField on a nested key: " + operation.key());
                };
        return setDeep(current, path.nested(), newValue);
    }

    private String arrayChange(String array, BridgeUpdate.Kind kind, Object value) {
        return switch (kind) {
            case PUSH -> "(" + array + " || jsonb_build_array(" + jsonParam(value) + "))";
            case ADD_TO_SET -> {
                final String element = jsonParam(value);
                yield "(CASE WHEN " + array + " @> jsonb_build_array(" + element + ") THEN " + array + " ELSE " + array
                        + " || jsonb_build_array(" + element + ") END)";
            }
            case PULL -> {
                // Like Mongo's $pull: an object value removes the elements that contain all its non-null fields.
                final JsonNode node = JSON.valueToTree(value);
                if (node instanceof ObjectNode object) {
                    final List<String> nullFields = new ArrayList<>();
                    object.fieldNames().forEachRemaining(name -> {
                        if (object.get(name).isNull()) {
                            nullFields.add(name);
                        }
                    });
                    object.remove(nullFields);
                    final String pattern = "CAST(" + param(object.toString()) + " AS jsonb)";
                    yield "COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(" + array
                            + ") AS e WHERE NOT (jsonb_typeof(e) = 'object' AND e @> " + pattern + ")), '[]'::jsonb)";
                }
                yield "COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(" + array + ") AS e WHERE e <> "
                        + jsonParam(value) + "), '[]'::jsonb)";
            }
            default -> throw new IllegalArgumentException(kind.name());
        };
    }

    /** {@code jsonb_set} that also creates the missing parent objects, as Mongo's $set does. */
    private static String setDeep(String json, List<String> path, String value) {
        String expression = "COALESCE(" + json + ", '{}'::jsonb)";
        for (int i = 1; i < path.size(); i++) {
            final String prefix = textArray(path.subList(0, i));
            expression = "jsonb_set(" + expression + ", " + prefix + ", CASE WHEN jsonb_typeof(" + expression + " #> "
                    + prefix + ") = 'object' THEN " + expression + " #> " + prefix + " ELSE '{}'::jsonb END, true)";
        }
        return "jsonb_set(" + expression + ", " + textArray(path) + ", " + value + ", true)";
    }
}

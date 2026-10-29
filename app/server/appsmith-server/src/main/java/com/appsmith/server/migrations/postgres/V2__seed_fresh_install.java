package com.appsmith.server.migrations.postgres;

import com.appsmith.external.helpers.ObjectIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fork: loads the data a fresh install starts with (plugins, system themes, the default organization, the instance
 * roles, the anonymous user and the instance config) from {@code db/seed/fresh-install.json}. That file is a snapshot
 * of a fresh install on MongoDB, after all Mongock migrations had run; see FORK.md, "PostgreSQL".
 * <p>
 * Keys are table names and each row's field names are the entity's field names; they map to the snake_case columns
 * of V1. Each value is cast to its column's type (objects and arrays to jsonb). Fields without a column are skipped,
 * so the snapshot may hold more than the schema does.
 */
@Slf4j
public class V2__seed_fresh_install extends BaseJavaMigration {

    static final String SEED = "db/seed/fresh-install.json";

    @Override
    public void migrate(Context context) throws Exception {
        final JsonNode seed;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SEED)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + SEED);
            }
            seed = new ObjectMapper().readTree(in);
        }

        final Connection connection = context.getConnection();
        final Iterator<Map.Entry<String, JsonNode>> tables = seed.fields();
        while (tables.hasNext()) {
            final Map.Entry<String, JsonNode> table = tables.next();
            final Map<String, String> columnTypes = columnTypes(connection, table.getKey());
            if (columnTypes.isEmpty()) {
                throw new IllegalStateException("Seed table " + table.getKey() + " does not exist");
            }
            for (JsonNode row : table.getValue()) {
                insert(connection, table.getKey(), columnTypes, row);
            }
            log.info("Seeded {} row(s) into {}", table.getValue().size(), table.getKey());
        }

        // The instance id is unique to each install, so it is not in the snapshot (Mongock generated it).
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO config (id, name, config, created_at, updated_at, policy_map)"
                        + " VALUES (?, 'instance-id', CAST(? AS jsonb), now(), now(), '{}'::jsonb)")) {
            statement.setString(1, ObjectIds.newId());
            statement.setString(2, "{\"value\": \"" + ObjectIds.newId() + "\"}");
            statement.executeUpdate();
        }
    }

    private static Map<String, String> columnTypes(Connection connection, String table) throws Exception {
        final Map<String, String> types = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return types;
    }

    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static void insert(Connection connection, String table, Map<String, String> columnTypes, JsonNode row)
            throws Exception {
        final List<String> columns = new ArrayList<>();
        final List<String> casts = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            final String column = snake(field.getKey());
            final String type = columnTypes.get(column);
            if (type == null) {
                log.debug("Seed: no column {}.{}, skipped", table, column);
                continue;
            }
            final JsonNode value = field.getValue();
            columns.add("\"" + column + "\"");
            casts.add("CAST(? AS " + castType(type) + ")");
            if (value.isNull()) {
                values.add(null);
            } else if ("jsonb".equals(type)) {
                values.add(value.toString());
            } else if (value.isContainerNode()) {
                throw new IllegalStateException("Seed " + table + "." + column + " is JSON but the column is " + type);
            } else {
                values.add(value.asText());
            }
        }
        final String sql = "INSERT INTO \"" + table + "\" (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", casts) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                statement.setString(i + 1, values.get(i));
            }
            statement.executeUpdate();
        }
    }

    private static String castType(String dataType) {
        return switch (dataType) {
            case "timestamp with time zone" -> "timestamptz";
            case "timestamp without time zone" -> "timestamp";
            case "character varying" -> "varchar";
            case "double precision" -> "float8";
            default -> dataType;
        };
    }
}

package com.appsmith.server.roles;

import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStorageDTO;

/**
 * Strips connection settings from a datasource before it reaches a user who may not manage it. What remains is what
 * a developer needs to pick and use a datasource: id, name, plugin, workspace, validity and configured state.
 * Host, database, URL, headers, properties and credentials all live in the configuration and are dropped.
 */
public final class DatasourceRedactor {

    private DatasourceRedactor() {}

    public static Datasource redact(Datasource datasource) {
        if (datasource == null) {
            return null;
        }
        // An empty configuration rather than null: the client reads nested fields of it without null checks.
        datasource.setDatasourceConfiguration(new DatasourceConfiguration());
        if (datasource.getDatasourceStorages() != null) {
            datasource.getDatasourceStorages().values().forEach(DatasourceRedactor::redact);
        }
        return datasource;
    }

    public static DatasourceStorageDTO redact(DatasourceStorageDTO storage) {
        if (storage != null) {
            storage.setDatasourceConfiguration(new DatasourceConfiguration());
        }
        return storage;
    }
}

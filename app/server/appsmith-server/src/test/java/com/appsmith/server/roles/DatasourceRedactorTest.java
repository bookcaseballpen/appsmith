package com.appsmith.server.roles;

import com.appsmith.external.models.DBAuth;
import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStorageDTO;
import com.appsmith.external.models.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceRedactorTest {

    @Test
    void should_dropConnectionSettingsButKeepIdentity_when_datasourceIsRedacted() {
        // Given
        DatasourceConfiguration configuration = new DatasourceConfiguration();
        configuration.setUrl("https://db.internal");
        configuration.setEndpoints(List.of(new Endpoint("db.internal", 5432L)));
        DBAuth auth = new DBAuth();
        auth.setUsername("app");
        auth.setPassword("secret");
        configuration.setAuthentication(auth);
        DatasourceStorageDTO storage = new DatasourceStorageDTO("storageId", "envId", configuration);

        Datasource datasource = new Datasource();
        datasource.setId("dsId");
        datasource.setName("Orders DB");
        datasource.setPluginId("postgres");
        datasource.setDatasourceConfiguration(configuration);
        datasource.setDatasourceStorages(Map.of("envId", storage));

        // When
        Datasource redacted = DatasourceRedactor.redact(datasource);

        // Then
        assertThat(redacted.getId()).isEqualTo("dsId");
        assertThat(redacted.getName()).isEqualTo("Orders DB");
        assertThat(redacted.getPluginId()).isEqualTo("postgres");
        assertThat(redacted.getDatasourceConfiguration())
                .usingRecursiveComparison()
                .isEqualTo(new DatasourceConfiguration());
        DatasourceStorageDTO redactedStorage = redacted.getDatasourceStorages().get("envId");
        assertThat(redactedStorage.getEnvironmentId()).isEqualTo("envId");
        assertThat(redactedStorage.getDatasourceConfiguration().getUrl()).isNull();
        assertThat(redactedStorage.getDatasourceConfiguration().getEndpoints()).isNull();
        assertThat(redactedStorage.getDatasourceConfiguration().getAuthentication())
                .isNull();
    }
}

package com.appsmith.server.solutions;

import com.appsmith.server.acl.AclPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourcePermissionImplTest {

    private final DatasourcePermissionImpl datasourcePermission = new DatasourcePermissionImpl();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_requireManage_when_exportingDatasourcesOutsideGit(boolean exportWithConfiguration) {
        // When
        AclPermission permission = datasourcePermission.getExportPermission(false, exportWithConfiguration);

        // Then
        assertThat(permission).isEqualTo(AclPermission.MANAGE_DATASOURCES);
    }

    @Test
    void should_requireCreateDatasourceActions_when_creatingQueryOnDatasource() {
        // When / Then
        StepVerifier.create(datasourcePermission.getActionCreatePermission())
                .expectNext(AclPermission.CREATE_DATASOURCE_ACTIONS)
                .verifyComplete();
    }
}

package com.external.plugins;

import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStorage;
import com.appsmith.external.services.SharedConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppsmithAiPluginExecutorTest {

    private static final String DISABLED = "cloud services disabled";

    private final SharedConfig disabledSharedConfig = new SharedConfig() {
        @Override
        public int getCodecSize() {
            return 10 * 1024 * 1024;
        }

        @Override
        public int getMaxResponseSize() {
            return 5 * 1024 * 1024;
        }

        @Override
        public String getRemoteExecutionUrl() {
            throw new IllegalStateException(DISABLED);
        }

        @Override
        public String getAIProxyBasePathUrl() {
            throw new IllegalStateException(DISABLED);
        }
    };

    @Test
    void should_createExecutor_when_aiProxyUrlUnavailable() {
        // Given / When / Then
        assertThatCode(() -> new AppsmithAiPlugin.AppsmithAiPluginExecutor(disabledSharedConfig))
                .doesNotThrowAnyException();
    }

    @Test
    void should_failAiCall_when_aiProxyUrlUnavailable() {
        // Given
        AppsmithAiPlugin.AppsmithAiPluginExecutor executor =
                new AppsmithAiPlugin.AppsmithAiPluginExecutor(disabledSharedConfig);
        DatasourceStorage datasourceStorage = new DatasourceStorage();
        datasourceStorage.setDatasourceConfiguration(new DatasourceConfiguration());

        // When / Then
        assertThatThrownBy(() -> executor.preSaveHook(datasourceStorage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DISABLED);
    }
}

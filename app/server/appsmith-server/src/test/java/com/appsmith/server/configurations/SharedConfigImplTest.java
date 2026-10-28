package com.appsmith.server.configurations;

import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharedConfigImplTest {

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @InjectMocks
    private SharedConfigImpl sharedConfig;

    static Stream<Arguments> cloudUrlGetters() {
        return Stream.of(
                Arguments.of("getRemoteExecutionUrl", (Function<SharedConfigImpl, String>)
                        SharedConfigImpl::getRemoteExecutionUrl),
                Arguments.of("getAIProxyBasePathUrl", (Function<SharedConfigImpl, String>)
                        SharedConfigImpl::getAIProxyBasePathUrl));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cloudUrlGetters")
    void should_throwCloudServicesError_when_cloudServicesDisabled(
            String name, Function<SharedConfigImpl, String> getter) {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When / Then
        assertThatThrownBy(() -> getter.apply(sharedConfig))
                .isInstanceOf(AppsmithException.class)
                .hasMessage(AppsmithError.CLOUD_SERVICES_ERROR.getMessage(CloudServicesConfig.DISABLED_MESSAGE));
        verify(cloudServicesConfig, never()).getBaseUrl();
    }

    @Test
    void should_buildUrlsFromBaseUrl_when_cloudServicesEnabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(true);
        when(cloudServicesConfig.getBaseUrl()).thenReturn("https://cs.example.com");

        // When
        String remoteExecutionUrl = sharedConfig.getRemoteExecutionUrl();
        String aiProxyBasePathUrl = sharedConfig.getAIProxyBasePathUrl();

        // Then
        assertThat(remoteExecutionUrl).isEqualTo("https://cs.example.com/api/v1/actions/execute");
        assertThat(aiProxyBasePathUrl).isEqualTo("https://cs.example.com");
    }
}

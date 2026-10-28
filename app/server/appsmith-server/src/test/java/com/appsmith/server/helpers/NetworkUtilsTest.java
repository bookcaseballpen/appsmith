package com.appsmith.server.helpers;

import com.appsmith.server.configurations.CloudServicesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkUtilsTest {

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @InjectMocks
    private NetworkUtils networkUtils;

    @Test
    void should_returnUnknownAddress_when_cloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<String> result = networkUtils.getExternalAddress();

        // Then
        StepVerifier.create(result).expectNext("unknown").verifyComplete();
        verify(cloudServicesConfig, never()).getBaseUrl();
    }
}

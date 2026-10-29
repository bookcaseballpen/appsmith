package com.appsmith.server.services;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.datasources.base.DatasourceService;
import com.appsmith.server.dtos.MockDataDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockDataServiceImplTest {

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @Mock
    private DatasourceService datasourceService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private SessionUserService sessionUserService;

    @InjectMocks
    private MockDataServiceImpl mockDataService;

    @Test
    void should_returnNoSampleDatabases_when_cloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<MockDataDTO> result = mockDataService.getMockDataSet();

        // Then
        StepVerifier.create(result)
                .assertNext(mockData -> assertThat(mockData.getMockdbs()).isEmpty())
                .verifyComplete();
        verify(cloudServicesConfig, never()).getBaseUrl();
    }
}

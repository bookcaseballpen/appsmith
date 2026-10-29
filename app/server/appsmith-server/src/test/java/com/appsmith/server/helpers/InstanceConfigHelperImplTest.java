package com.appsmith.server.helpers;

import com.appsmith.external.services.RTSCaller;
import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.Config;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.services.FeatureFlagService;
import com.appsmith.server.services.OrganizationService;
import com.appsmith.server.solutions.ReleaseNotesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import com.appsmith.server.repositories.postgres.PostgresOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstanceConfigHelperImplTest {

    @Mock
    private ConfigService configService;

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @Mock
    private CommonConfig commonConfig;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private PostgresOperations postgresOperations;

    @Mock
    private FeatureFlagService featureFlagService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private NetworkUtils networkUtils;

    @Mock
    private ReleaseNotesService releaseNotesService;

    @Mock
    private RTSCaller rtsCaller;

    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private InstanceConfigHelperImpl instanceConfigHelper;

    @Test
    void should_skipRegistration_when_cloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<? extends Config> result = instanceConfigHelper.registerInstance();

        // Then
        StepVerifier.create(result).verifyComplete();
        verifyNoInteractions(configService, analyticsService, networkUtils);
    }
}

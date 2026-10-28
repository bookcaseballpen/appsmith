package com.appsmith.server.helpers;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.services.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitCloudServicesUtilsTest {

    @Mock
    private CommonConfig commonConfig;

    @Mock
    private ConfigService configService;

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @InjectMocks
    private GitCloudServicesUtils gitCloudServicesUtils;

    @Test
    void should_returnNoLimit_when_cloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<Integer> result = gitCloudServicesUtils.getPrivateRepoLimitForOrg("workspaceId", true);

        // Then
        StepVerifier.create(result).expectNext(-1).verifyComplete();
        verifyNoInteractions(configService);
    }
}

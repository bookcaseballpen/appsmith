package com.appsmith.server.services;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.domains.User;
import com.appsmith.server.solutions.ReleaseNotesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CacheablePylonHelperImplTest {

    @Mock
    private ConfigService configService;

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @Mock
    private ReleaseNotesService releaseNotesService;

    @InjectMocks
    private CacheablePylonHelperImpl cacheablePylonHelper;

    @Test
    void should_returnNoEmailHash_when_userHasEmail() {
        // Given
        User user = new User();
        user.setEmail("user@example.com");

        // When
        Mono<String> result = cacheablePylonHelper.getEmailHash(user);

        // Then
        StepVerifier.create(result).verifyComplete();
        verifyNoInteractions(configService, cloudServicesConfig);
    }
}

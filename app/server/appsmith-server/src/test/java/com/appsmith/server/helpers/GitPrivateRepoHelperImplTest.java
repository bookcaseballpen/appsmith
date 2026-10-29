package com.appsmith.server.helpers;

import com.appsmith.server.applications.base.ApplicationService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class GitPrivateRepoHelperImplTest {

    @Mock
    private GitCloudServicesUtils gitCloudServicesUtils;

    @Mock
    private ApplicationService applicationService;

    @InjectMocks
    private GitPrivateRepoHelperImpl gitPrivateRepoHelper;

    // isClearCache is true for connect and import, false for commit.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_neverReachRepoLimit_when_checkedForAnyFlow(boolean isClearCache) {
        // When
        Mono<Boolean> result = gitPrivateRepoHelper.isRepoLimitReached("workspaceId", isClearCache);

        // Then
        StepVerifier.create(result).expectNext(Boolean.FALSE).verifyComplete();
        verifyNoInteractions(gitCloudServicesUtils, applicationService);
    }
}

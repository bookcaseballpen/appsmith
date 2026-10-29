package com.appsmith.server.services;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.FeaturesRequestDTO;
import com.appsmith.server.dtos.FeaturesResponseDTO;
import com.appsmith.server.featureflags.CachedFlags;
import com.appsmith.server.featureflags.ForkFeatureFlagDefaults;
import com.appsmith.server.repositories.OrganizationRepository;
import com.appsmith.server.solutions.ReleaseNotesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheableFeatureFlagHelperImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ConfigService configService;

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @Mock
    private CommonConfig commonConfig;

    @Mock
    private UserIdentifierService userIdentifierService;

    @Mock
    private ReleaseNotesService releaseNotesService;

    @InjectMocks
    private CacheableFeatureFlagHelperImpl cacheableFeatureFlagHelper;

    @Test
    void should_returnLocalDefaults_when_fetchingUserFlagsWithCloudDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<CachedFlags> result = cacheableFeatureFlagHelper.fetchUserCachedFlags("userIdentifier", new User());

        // Then
        StepVerifier.create(result)
                .assertNext(cachedFlags -> {
                    assertThat(cachedFlags.getFlags()).isEqualTo(ForkFeatureFlagDefaults.get());
                    assertThat(cachedFlags.getRefreshedAt()).isNotNull();
                })
                .verifyComplete();
        verifyNoInteractions(configService, organizationRepository);
    }

    @Test
    void should_returnLocalDefaults_when_fetchingOrganizationFeaturesWithCloudDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);

        // When
        Mono<FeaturesResponseDTO> result =
                cacheableFeatureFlagHelper.getRemoteFeaturesForOrganization(new FeaturesRequestDTO());

        // Then
        StepVerifier.create(result)
                .assertNext(response -> assertThat(response.getFeatures()).isEqualTo(ForkFeatureFlagDefaults.get()))
                .verifyComplete();
    }

    @Test
    void should_returnOrganizationDefaults_when_cachedOrganizationFeaturesAreFetchedWithCloudDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);
        when(configService.getInstanceId()).thenReturn(Mono.just("instanceId"));

        // When
        Mono<Boolean> gitProtectionFlag = cacheableFeatureFlagHelper
                .fetchCachedOrganizationFeatures("organizationId")
                .map(cachedFeatures -> cachedFeatures.getFeatures().get("license_git_branch_protection_enabled"));

        // Then
        StepVerifier.create(gitProtectionFlag).expectNext(true).verifyComplete();
    }
}

package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.featureflags.CachedFeatures;
import com.appsmith.server.helpers.FeatureFlagMigrationHelper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceImplTest {

    @Mock
    SessionUserService sessionUserService;

    @Mock
    OrganizationService organizationService;

    @Mock
    UserIdentifierService userIdentifierService;

    @Mock
    CacheableFeatureFlagHelper cacheableFeatureFlagHelper;

    @Mock
    FeatureFlagMigrationHelper featureFlagMigrationHelper;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_sendTheBuiltInAiSwitch_when_clientAsksForFlags(boolean builtInAiEnabled) {
        // Given: a cached organization flag with the opposite value, which the switch must override.
        when(sessionUserService.getCurrentUser()).thenReturn(Mono.empty());
        when(organizationService.getCurrentUserOrganizationId()).thenReturn(Mono.just("org-1"));
        when(cacheableFeatureFlagHelper.fetchCachedOrganizationFeatures("org-1"))
                .thenReturn(Mono.just(new CachedFeatures(
                        Map.of(BuiltInAiConfig.FEATURE_FLAG, !builtInAiEnabled, "some_other_flag", true),
                        Instant.now())));
        FeatureFlagServiceImpl service = new FeatureFlagServiceImpl(
                sessionUserService,
                organizationService,
                userIdentifierService,
                cacheableFeatureFlagHelper,
                featureFlagMigrationHelper,
                new BuiltInAiConfig(builtInAiEnabled));

        // When
        Mono<Map<String, Boolean>> flags = service.getAllFeatureFlagsForUser();

        // Then
        StepVerifier.create(flags)
                .assertNext(map -> assertThat(map)
                        .containsEntry(BuiltInAiConfig.FEATURE_FLAG, builtInAiEnabled)
                        .containsEntry("some_other_flag", true))
                .verifyComplete();
    }
}

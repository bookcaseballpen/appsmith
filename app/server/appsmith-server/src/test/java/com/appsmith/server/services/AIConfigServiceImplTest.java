package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.dtos.AIConfigDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIConfigServiceImplTest {

    enum Endpoint {
        UPDATE,
        GET,
        TEST_CONNECTION,
        FETCH_MODELS,
        TEST_API_KEY
    }

    @Mock
    OrganizationService organizationService;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    AnalyticsService analyticsService;

    @Mock
    SessionUserService sessionUserService;

    private AIConfigServiceImpl service(boolean builtInAiEnabled) {
        return new AIConfigServiceImpl(
                organizationService,
                objectMapper,
                analyticsService,
                sessionUserService,
                new BuiltInAiConfig(builtInAiEnabled));
    }

    private static Mono<Map<String, Object>> call(AIConfigServiceImpl service, Endpoint endpoint) {
        return switch (endpoint) {
            case UPDATE -> service.updateAIConfig(new AIConfigDTO());
            case GET -> service.getAIConfig();
            case TEST_CONNECTION -> service.testLlmConnection(Map.of());
            case FETCH_MODELS -> service.fetchLlmModels(Map.of());
            case TEST_API_KEY -> service.testApiKey(Map.of());
        };
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void should_refuseWithFeatureDisabled_when_builtInAiIsSwitchedOff(Endpoint endpoint) {
        // When
        Mono<Map<String, Object>> result = call(service(false), endpoint);

        // Then
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AppsmithException.class);
                    assertThat(((AppsmithException) error).getError()).isEqualTo(AppsmithError.FEATURE_DISABLED);
                })
                .verify();
        verifyNoInteractions(organizationService, sessionUserService);
    }

    @Test
    void should_reachTheSettings_when_builtInAiIsSwitchedOn() {
        // Given: no current organization, so the CE lookup completes empty right after the switch.
        when(organizationService.getCurrentUserOrganizationId()).thenReturn(Mono.empty());

        // When
        Mono<Map<String, Object>> result = service(true).getAIConfig();

        // Then
        StepVerifier.create(result).verifyComplete();
        verify(organizationService).getCurrentUserOrganizationId();
    }
}

package com.appsmith.server.services;

import com.appsmith.external.constants.AnalyticsEvents;
import com.appsmith.server.domains.Application;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsServiceImplTest {

    private final AnalyticsServiceImpl analyticsService = new AnalyticsServiceImpl();

    @Test
    void should_beInactive_when_created() {
        // When
        boolean active = analyticsService.isActive();

        // Then
        assertThat(active).isFalse();
    }

    @Test
    void should_returnObjectUnchanged_when_objectEventIsSent() {
        // Given
        Application application = new Application();
        application.setName("app");

        // When
        Mono<Application> result =
                analyticsService.sendObjectEvent(AnalyticsEvents.CREATE, application, Map.of("key", "value"));

        // Then
        StepVerifier.create(result).expectNext(application).verifyComplete();
    }

    @Test
    void should_completeEmpty_when_eventIsSent() {
        // When
        Mono<Void> result = analyticsService.sendEvent("event", "user@example.com", Map.of());

        // Then
        StepVerifier.create(result).verifyComplete();
    }
}

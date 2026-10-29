package com.appsmith.server.configurations;

import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInAiConfigTest {

    @Test
    void should_refuseWithFeatureDisabled_when_switchIsOff() {
        // Given
        BuiltInAiConfig config = new BuiltInAiConfig(false);

        // When / Then
        assertThat(config.isEnabled()).isFalse();
        StepVerifier.create(config.requireEnabled())
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AppsmithException.class);
                    assertThat(((AppsmithException) error).getError()).isEqualTo(AppsmithError.FEATURE_DISABLED);
                    assertThat(error.getMessage()).isEqualTo("The built-in AI is disabled on this instance.");
                })
                .verify();
    }

    @Test
    void should_complete_when_switchIsOn() {
        // Given
        BuiltInAiConfig config = new BuiltInAiConfig(true);

        // When / Then
        assertThat(config.isEnabled()).isTrue();
        StepVerifier.create(config.requireEnabled()).verifyComplete();
    }
}

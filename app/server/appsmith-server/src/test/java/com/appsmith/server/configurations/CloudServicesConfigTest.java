package com.appsmith.server.configurations;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CloudServicesConfigTest {

    private static CloudServicesConfig config(boolean enabled, String baseUrl) {
        CloudServicesConfig config = new CloudServicesConfig();
        ReflectionTestUtils.setField(config, "enabled", enabled);
        ReflectionTestUtils.setField(config, "baseUrlWithSignatureVerification", "");
        config.setBaseUrl(baseUrl);
        return config;
    }

    @Test
    void should_returnEmptyBaseUrls_when_disabled() {
        // Given
        CloudServicesConfig config = config(false, "");

        // When
        String baseUrl = config.getBaseUrl();
        String signatureBaseUrl = config.getBaseUrlWithSignatureVerification();

        // Then
        assertThat(config.isEnabled()).isFalse();
        assertThat(baseUrl).isEmpty();
        assertThat(signatureBaseUrl).isEmpty();
    }

    @Test
    void should_returnUpstreamDefault_when_enabledWithoutBaseUrl() {
        // Given
        CloudServicesConfig config = config(true, "");

        // When
        String baseUrl = config.getBaseUrl();

        // Then
        assertThat(baseUrl).isEqualTo("https://cs.appsmith.com");
        assertThat(config.getBaseUrlWithSignatureVerification()).isEqualTo("https://cs.appsmith.com");
    }

    @Test
    void should_returnConfiguredBaseUrl_when_enabled() {
        // Given
        CloudServicesConfig config = config(true, "https://cs.example.com");

        // When
        String baseUrl = config.getBaseUrl();

        // Then
        assertThat(baseUrl).isEqualTo("https://cs.example.com");
    }
}

package com.appsmith.server.controllers;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.solutions.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaasControllerTest {

    @Mock
    private AuthenticationService authenticationService;

    @Mock
    private CloudServicesConfig cloudServicesConfig;

    @Test
    void should_failWithCloudServicesError_when_authorizeCalledWithCloudServicesDisabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(false);
        SaasController controller = new SaasController(authenticationService, cloudServicesConfig);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/saas/authorize"));

        // When
        Mono<Void> result = controller.redirectForAuthorize(exchange, "token");

        // Then
        String expectedMessage = AppsmithError.CLOUD_SERVICES_ERROR.getMessage(CloudServicesConfig.DISABLED_MESSAGE);
        StepVerifier.create(result)
                .expectErrorSatisfies(error ->
                        assertThat(error).isInstanceOf(AppsmithException.class).hasMessage(expectedMessage))
                .verify();
        assertThat(exchange.getResponse().getHeaders().getLocation()).isNull();
        verify(cloudServicesConfig, never()).getBaseUrl();
    }

    @Test
    void should_redirectToCloudServices_when_authorizeCalledWithCloudServicesEnabled() {
        // Given
        when(cloudServicesConfig.isEnabled()).thenReturn(true);
        when(cloudServicesConfig.getBaseUrl()).thenReturn("https://cs.example.com");
        SaasController controller = new SaasController(authenticationService, cloudServicesConfig);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/saas/authorize"));

        // When
        Mono<Void> result = controller.redirectForAuthorize(exchange, "token");

        // Then
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(exchange.getResponse().getHeaders().getLocation())
                .hasToString("https://cs.example.com/api/v1/integrations/oauth/authorize?appsmithToken=token");
    }
}

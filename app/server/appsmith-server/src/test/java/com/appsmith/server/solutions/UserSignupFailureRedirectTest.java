package com.appsmith.server.solutions;

import com.appsmith.server.authentication.handlers.AuthenticationSuccessHandler;
import com.appsmith.server.helpers.NetworkUtils;
import com.appsmith.server.helpers.UserUtils;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.CaptchaService;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.services.EmailService;
import com.appsmith.server.services.OrganizationService;
import com.appsmith.server.services.UserDataService;
import com.appsmith.server.services.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Fork: a failed first-admin signup redirects to this instance's own login page when the request has no Referer,
 * never to app.appsmith.com.
 */
@ExtendWith(MockitoExtension.class)
class UserSignupFailureRedirectTest {

    @Mock
    private UserService userService;

    @Mock
    private UserDataService userDataService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private AuthenticationSuccessHandler authenticationSuccessHandler;

    @Mock
    private ConfigService configService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private EnvManager envManager;

    @Mock
    private UserUtils userUtils;

    @Mock
    private NetworkUtils networkUtils;

    @Mock
    private EmailService emailService;

    @Mock
    private OrganizationService organizationService;

    private UserSignupImpl userSignup;

    @BeforeEach
    void setUp() {
        userSignup = new UserSignupImpl(
                userService,
                userDataService,
                captchaService,
                authenticationSuccessHandler,
                configService,
                analyticsService,
                envManager,
                userUtils,
                networkUtils,
                emailService,
                organizationService);
    }

    @Test
    void should_redirectToOwnLoginPage_when_superSignupFailsWithoutReferer() {
        // Given: the first-admin slot is already taken, so the signup fails.
        when(userService.claimSuperUserCreationSlot()).thenReturn(Mono.just(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/users/super")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("email=admin%40example.com&password=Secret%232026"));

        // When
        Mono<Void> result = userSignup.signupAndLoginSuperFromFormData(null, exchange);

        // Then
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        URI location = exchange.getResponse().getHeaders().getLocation();
        assertThat(location).isNotNull();
        assertThat(location.getHost()).isNull();
        assertThat(location.getPath()).isEqualTo("/user/login");
        assertThat(location.getQuery()).startsWith("error=");
    }
}

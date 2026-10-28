package com.appsmith.server.roles;

import com.appsmith.server.domains.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApproverReadOnlyWebFilterTest {

    @Mock
    InstanceRoleService instanceRoleService;

    @ParameterizedTest
    @CsvSource({
        "PUT, /api/v1/layouts/layoutId/pages/pageId",
        "POST, /api/v1/actions",
        "PUT, /api/v1/applications/appId",
        "DELETE, /api/v1/pages/pageId",
        "POST, /api/v1/datasources",
        "POST, /api/v1/users/ai-assistant/request",
        "POST, /api/v1/users/mcp-tokens",
        "POST, /api/v1/git/applications/appId/commit",
    })
    void should_refuseWithForbidden_when_approverChangesSomething(String method, String path) {
        // Given
        when(instanceRoleService.getRole(any())).thenReturn(Mono.just(InstanceRole.APPROVER));
        MockServerWebExchange exchange = exchange(method, path);
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(
                        runAsUser(new ApproverReadOnlyWebFilter(instanceRoleService).filter(exchange, chain(passed))))
                .verifyComplete();

        // Then
        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/applications/publish/appId",
        "POST, /api/v1/actions/execute",
        "POST, /api/v1/logout",
        "GET, /api/v1/pages/pageId",
        "GET, /api/v1/datasources",
    })
    void should_passThrough_when_approverReadsPublishesOrRunsQueries(String method, String path) {
        // Given
        MockServerWebExchange exchange = exchange(method, path);
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(
                        runAsUser(new ApproverReadOnlyWebFilter(instanceRoleService).filter(exchange, chain(passed))))
                .verifyComplete();

        // Then
        assertThat(passed).isTrue();
        verifyNoInteractions(instanceRoleService);
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"ADMIN", "DEV", "USER"})
    void should_leaveDecisionToServices_when_otherRoleChangesSomething(InstanceRole role) {
        // Given
        when(instanceRoleService.getRole(any())).thenReturn(Mono.just(role));
        MockServerWebExchange exchange = exchange("PUT", "/api/v1/applications/appId");
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(
                        runAsUser(new ApproverReadOnlyWebFilter(instanceRoleService).filter(exchange, chain(passed))))
                .verifyComplete();

        // Then
        assertThat(passed).isTrue();
    }

    @Test
    void should_passThrough_when_requestIsAnonymous() {
        // Given
        MockServerWebExchange exchange = exchange("POST", "/api/v1/users");
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(new ApproverReadOnlyWebFilter(instanceRoleService).filter(exchange, chain(passed)))
                .verifyComplete();

        // Then
        assertThat(passed).isTrue();
    }

    private static MockServerWebExchange exchange(String method, String path) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());
    }

    private static WebFilterChain chain(AtomicBoolean passed) {
        return exchange -> {
            passed.set(true);
            return Mono.empty();
        };
    }

    private static Mono<Void> runAsUser(Mono<Void> filtered) {
        User user = new User();
        user.setId("userId");
        user.setEmail("someone@roles.test");
        return filtered.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())));
    }
}

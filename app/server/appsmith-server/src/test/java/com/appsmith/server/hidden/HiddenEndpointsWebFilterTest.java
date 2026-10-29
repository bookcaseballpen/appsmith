package com.appsmith.server.hidden;

import com.appsmith.server.configurations.BuiltInGitConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HiddenEndpointsWebFilterTest {

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/git/applications/app1/metadata",
        "POST, /api/v1/git/applications/app1/connect",
        "POST, /api/v1/git/applications/app1/commit",
        "GET, /api/v1/git/applications/app1/pull",
        "GET, /api/v1/git/applications/app1/refs",
        "POST, /api/v1/git/applications/app1/ssh-keypair",
        "POST, /api/v1/git/artifacts/import",
        "GET, /api/v1/git/artifacts/import/keys",
        "GET, /api/v1/git/artifacts/profile/default",
        "GET, /api/v1/applications/ssh-keypair/app1",
        "POST, /api/v1/applications/ssh-keypair/app1",
    })
    void should_refuseGit_when_builtInGitIsOff(String method, String path) {
        // When
        var feature = HiddenEndpointsWebFilter.hiddenFeature(
                HttpMethod.valueOf(method), PathContainer.parsePath(path), false);

        // Then
        assertThat(feature).contains(BuiltInGitConfig.FEATURE_NAME);
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/git/applications/app1/metadata",
        "POST, /api/v1/git/artifacts/import",
        "GET, /api/v1/applications/ssh-keypair/app1",
    })
    void should_allowGit_when_builtInGitIsOn(String method, String path) {
        // When
        var feature =
                HiddenEndpointsWebFilter.hiddenFeature(HttpMethod.valueOf(method), PathContainer.parsePath(path), true);

        // Then
        assertThat(feature).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/app-templates",
        "GET, /api/v1/app-templates/filters",
        "GET, /api/v1/app-templates/t1",
        "GET, /api/v1/app-templates/t1/similar",
        "POST, /api/v1/app-templates/t1/import/ws1",
        "POST, /api/v1/app-templates/t1/merge/app1/ws1",
        "POST, /api/v1/app-templates/publish/community-template",
    })
    void should_refuseTemplates_when_gitSwitchHasAnyValue(String method, String path) {
        // When
        var withGitOn =
                HiddenEndpointsWebFilter.hiddenFeature(HttpMethod.valueOf(method), PathContainer.parsePath(path), true);
        var withGitOff = HiddenEndpointsWebFilter.hiddenFeature(
                HttpMethod.valueOf(method), PathContainer.parsePath(path), false);

        // Then
        assertThat(withGitOn).contains(HiddenEndpointsWebFilter.TEMPLATES_FEATURE_NAME);
        assertThat(withGitOff).contains(HiddenEndpointsWebFilter.TEMPLATES_FEATURE_NAME);
    }

    @ParameterizedTest
    @CsvSource({"POST, /api/v1/workspaces", "DELETE, /api/v1/workspaces/ws1"})
    void should_refuseCreatingOrDeletingWorkspaces_when_requested(String method, String path) {
        // When
        var feature =
                HiddenEndpointsWebFilter.hiddenFeature(HttpMethod.valueOf(method), PathContainer.parsePath(path), true);

        // Then
        assertThat(feature).contains(HiddenEndpointsWebFilter.WORKSPACES_FEATURE_NAME);
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/workspaces/home",
        "GET, /api/v1/workspaces/ws1",
        "PUT, /api/v1/workspaces/ws1",
        "GET, /api/v1/workspaces/ws1/members",
        "POST, /api/v1/applications",
        "POST, /api/v1/applications/import/ws1",
        "GET, /api/v1/applications/export/app1",
        "POST, /api/v1/applications/publish/app1",
        "GET, /api/v1/users/me",
        "GET, /api/v1/admin/users",
    })
    void should_letOtherEndpointsThrough_when_gitIsOff(String method, String path) {
        // When
        var feature = HiddenEndpointsWebFilter.hiddenFeature(
                HttpMethod.valueOf(method), PathContainer.parsePath(path), false);

        // Then
        assertThat(feature).isEmpty();
    }

    @Test
    void should_answerFeatureDisabled_when_requestIsRefused() {
        // Given
        HiddenEndpointsWebFilter filter = new HiddenEndpointsWebFilter(new BuiltInGitConfig(false));
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/git/applications/app1/commit")
                        .build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ignored -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();
        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"AE-APP-4030\"")
                        .contains("\"message\":\"Built-in Git is disabled on this instance.\""))
                .verifyComplete();
    }

    @Test
    void should_passTheRequestOn_when_endpointIsNotHidden() {
        // Given
        HiddenEndpointsWebFilter filter = new HiddenEndpointsWebFilter(new BuiltInGitConfig(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/workspaces/home").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ignored -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        // When
        Mono<Void> result = filter.filter(exchange, chain);

        // Then
        StepVerifier.create(result).verifyComplete();
        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}

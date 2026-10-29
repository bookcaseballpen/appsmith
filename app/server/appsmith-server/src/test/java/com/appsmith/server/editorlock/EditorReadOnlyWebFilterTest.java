package com.appsmith.server.editorlock;

import com.appsmith.server.authentication.tokens.McpTokenAuthentication;
import com.appsmith.server.domains.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EditorReadOnlyWebFilterTest {

    private static final EditorReadOnlyWebFilter ENABLED = new EditorReadOnlyWebFilter(new EditorReadOnlySetting(true));

    @ParameterizedTest
    @CsvSource({
        "PUT, /api/v1/layouts/layoutId/pages/pageId",
        "PUT, /api/v1/layouts/refactor",
        "PUT, /api/v1/layouts/application/appId",
        "POST, /api/v1/actions",
        "PUT, /api/v1/actions/actionId",
        "PUT, /api/v1/actions/move",
        "PUT, /api/v1/actions/runBehaviour/actionId",
        "DELETE, /api/v1/actions/actionId",
        "POST, /api/v1/collections/actions",
        "PATCH, /api/v1/collections/actions/collectionId",
        "PUT, /api/v1/collections/actions/collectionId/body",
        "DELETE, /api/v1/collections/actions/collectionId",
        "POST, /api/v1/pages",
        "PUT, /api/v1/pages/pageId",
        "DELETE, /api/v1/pages/pageId",
        "POST, /api/v1/pages/clone/pageId",
        "POST, /api/v1/pages/crud-page",
        "PUT, /api/v1/themes/applications/appId",
        "PATCH, /api/v1/themes/themeId",
        "PATCH, /api/v1/libraries/appId/add",
        "POST, /api/v1/import",
        "PUT, /api/v1/applications/appId",
        "PUT, /api/v1/applications/appId/page/pageId/makeDefault",
        "PUT, /api/v1/applications/appId/page/pageId/reorder",
        "PATCH, /api/v1/applications/appId/themes/themeId",
        "POST, /api/v1/applications/appId/logo",
        "PATCH, /api/v1/applications/appId/static-url",
        "POST, /api/v1/applications/import/partial/block",
        "POST, /api/v1/app-templates/templateId/merge/appId/workspaceId",
    })
    void should_refuseWithMessage_when_sessionUserChangesApp(String method, String path) {
        // Given
        MockServerWebExchange exchange = exchange(method, path);
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(ENABLED.filter(exchange, chain(passed)).contextWrite(as(sessionUser())))
                .verifyComplete();

        // Then
        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains(EditorReadOnlyWebFilter.MESSAGE))
                .verifyComplete();
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/actions/execute",
        "POST, /api/v1/applications/import/workspaceId",
        "POST, /api/v1/applications/import/partial/workspaceId/appId",
        "POST, /api/v1/applications/export/partial/appId/pageId",
        "POST, /api/v1/applications/publish/appId",
        "POST, /api/v1/applications",
        "DELETE, /api/v1/applications/appId",
        "POST, /api/v1/git/applications/appId/commit",
        "POST, /api/v1/datasources",
        "PUT, /api/v1/users",
        "GET, /api/v1/pages/pageId",
        "GET, /api/v1/layouts/layoutId/pages/pageId",
        "GET, /api/v1/actions",
    })
    void should_passThrough_when_sessionUserRunsReadsImportsOrPublishes(String method, String path) {
        // Given
        MockServerWebExchange exchange = exchange(method, path);
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(ENABLED.filter(exchange, chain(passed)).contextWrite(as(sessionUser())))
                .verifyComplete();

        // Then
        assertThat(passed).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "PUT, /api/v1/layouts/layoutId/pages/pageId",
        "POST, /api/v1/actions",
        "PATCH, /api/v1/collections/actions/collectionId",
        "PUT, /api/v1/themes/applications/appId",
    })
    void should_passThrough_when_mcpPrincipalChangesApp(String method, String path) {
        // Given
        MockServerWebExchange exchange = exchange(method, path);
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(ENABLED.filter(exchange, chain(passed)).contextWrite(as(mcpPrincipal())))
                .verifyComplete();

        // Then
        assertThat(passed).isTrue();
    }

    @Test
    void should_refuse_when_requestIsAnonymous() {
        // Given
        MockServerWebExchange exchange = exchange("PUT", "/api/v1/layouts/layoutId/pages/pageId");
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(ENABLED.filter(exchange, chain(passed))).verifyComplete();

        // Then
        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void should_passThrough_when_settingIsOff() {
        // Given
        EditorReadOnlyWebFilter disabled = new EditorReadOnlyWebFilter(new EditorReadOnlySetting(false));
        MockServerWebExchange exchange = exchange("PUT", "/api/v1/layouts/layoutId/pages/pageId");
        AtomicBoolean passed = new AtomicBoolean();

        // When
        StepVerifier.create(disabled.filter(exchange, chain(passed)).contextWrite(as(sessionUser())))
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

    private static Context as(Authentication authentication) {
        return ReactiveSecurityContextHolder.withAuthentication(authentication);
    }

    private static Authentication sessionUser() {
        User user = user();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    private static Authentication mcpPrincipal() {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(McpTokenAuthentication.MCP_AUTHORITY));
        return UsernamePasswordAuthenticationToken.authenticated(user(), null, authorities);
    }

    private static User user() {
        User user = new User();
        user.setId("userId");
        user.setEmail("someone@readonly.test");
        return user;
    }
}

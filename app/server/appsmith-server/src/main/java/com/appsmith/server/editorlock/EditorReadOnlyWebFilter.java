package com.appsmith.server.editorlock;

import com.appsmith.server.authentication.tokens.McpTokenAuthentication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Fork: keeps the editor read-only for every role, Admin included. Apps are built in the projects repo and change
 * only through app import (always open) or the MCP service, whose requests authenticate with an {@code mcp_} bearer
 * token plus the internal marker header (see {@code McpTokenAuthenticationConverter}). Such a request carries
 * {@link McpTokenAuthentication#MCP_AUTHORITY}, which a browser session cannot get, so it is the one caller let
 * through. {@code McpAllowlistWebFilter} still confines it to the MCP tool endpoints.
 *
 * <p>Refused: the endpoints the editor uses to change an app's content (layouts and widgets, pages, queries and
 * APIs, JS objects, themes, JS libraries, app settings, navigation logo, static URLs, building blocks, curl import,
 * template merge). Still open: running queries ({@code POST /api/v1/actions/execute}), reading, import, export,
 * publish, git, datasources, app lifecycle (create, clone, fork, delete, snapshots) and user and admin settings.
 *
 * <p>Off when {@code APPSMITH_EDITOR_READ_ONLY=false}. Ordered last, after Spring Security's
 * {@code WebFilterChainProxy}, so the authentication is in the security context when this filter runs.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class EditorReadOnlyWebFilter implements WebFilter {

    static final String MESSAGE = "This app is edited through the projects repo (MCP/import); the editor is read-only.";

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final Set<HttpMethod> WRITES =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private static final List<Rule> LOCKED = List.of(
            // Queries and APIs: create, update, rename, move, refactor, run behaviour, delete.
            new Rule(WRITES, PARSER.parse("/api/v1/actions/**")),
            // JS objects.
            new Rule(WRITES, PARSER.parse("/api/v1/collections/**")),
            // Page layouts (widgets and their properties) and widget renames.
            new Rule(WRITES, PARSER.parse("/api/v1/layouts/**")),
            // Pages: create, generate (crud-page), clone, rename, delete, static URL.
            new Rule(WRITES, PARSER.parse("/api/v1/pages/**")),
            new Rule(WRITES, PARSER.parse("/api/v1/themes/**")),
            new Rule(WRITES, PARSER.parse("/api/v1/libraries/**")),
            // Curl import, which creates a query.
            new Rule(WRITES, PARSER.parse("/api/v1/import")),
            // App settings (name, icon, layout, navigation) and page order and default page.
            new Rule(Set.of(HttpMethod.PUT), PARSER.parse("/api/v1/applications/{applicationId}")),
            new Rule(Set.of(HttpMethod.PUT), PARSER.parse("/api/v1/applications/{applicationId}/page/**")),
            new Rule(Set.of(HttpMethod.PATCH), PARSER.parse("/api/v1/applications/{applicationId}/themes/**")),
            new Rule(WRITES, PARSER.parse("/api/v1/applications/{applicationId}/logo")),
            new Rule(WRITES, PARSER.parse("/api/v1/applications/{applicationId}/static-url")),
            new Rule(Set.of(HttpMethod.POST), PARSER.parse("/api/v1/applications/import/partial/block")),
            new Rule(Set.of(HttpMethod.POST), PARSER.parse("/api/v1/app-templates/{templateId}/merge/**")));

    private static final List<Rule> OPEN = List.of(
            // Running a saved query against its saved datasource; it does not change the app.
            new Rule(Set.of(HttpMethod.POST), PARSER.parse("/api/v1/actions/execute")));

    private final EditorReadOnlySetting setting;

    public EditorReadOnlyWebFilter(EditorReadOnlySetting setting) {
        this.setting = setting;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!setting.isEnabled()
                || !isLocked(
                        exchange.getRequest().getMethod(),
                        exchange.getRequest().getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(McpTokenAuthentication::isMcpPrincipal)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(isMcp -> isMcp ? chain.filter(exchange) : forbidden(exchange));
    }

    static boolean isLocked(HttpMethod method, PathContainer path) {
        return LOCKED.stream().anyMatch(rule -> rule.matches(method, path))
                && OPEN.stream().noneMatch(rule -> rule.matches(method, path));
    }

    private static Mono<Void> forbidden(ServerWebExchange exchange) {
        log.debug(
                "Read-only editor refused {} {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"responseMeta\":{\"status\":403,\"success\":false,\"error\":{\"message\":\"" + MESSAGE
                        + "\"}},\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private record Rule(Set<HttpMethod> methods, PathPattern pattern) {
        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return methods.contains(requestMethod) && pattern.matches(path);
        }
    }
}

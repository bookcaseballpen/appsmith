package com.appsmith.server.hidden;

import com.appsmith.server.configurations.BuiltInGitConfig;
import com.appsmith.server.exceptions.AppsmithError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Fork: refuses the API endpoints of the features this fork hides, with {@link AppsmithError#FEATURE_DISABLED}
 * (HTTP 403, {@code AE-APP-4030}). The code behind them stays; only the HTTP entry points are closed. See FORK.md,
 * "Hidden features".
 *
 * <ul>
 *   <li>Built-in Git, while {@link BuiltInGitConfig} ({@code APPSMITH_GIT_ENABLED}) is off: every
 *       {@code /api/v1/git/**} endpoint (connect, branches, commit, pull, merge, deploy keys, import from Git, Git
 *       profiles) and the application deploy-key endpoints.
 *   <li>Templates, always: every {@code /api/v1/app-templates} endpoint (gallery, fork, add page from template,
 *       publish).
 *   <li>Workspaces, always: creating and deleting a workspace. The single default workspace is created by the fork's
 *       startup code ({@link com.appsmith.server.roles.DefaultWorkspaceService}), straight through the repository,
 *       so it does not pass here; neither do server-internal calls of the workspace service.
 * </ul>
 *
 * <p>Ordered after Spring Security's {@code WebFilterChainProxy} (order -100), so an anonymous caller still gets 401
 * first, and before {@link com.appsmith.server.roles.ApproverReadOnlyWebFilter}.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class HiddenEndpointsWebFilter implements WebFilter {

    static final String TEMPLATES_FEATURE_NAME = "The template gallery";

    static final String WORKSPACES_FEATURE_NAME = "Creating or deleting workspaces";

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<PathPattern> GIT_PATHS = List.of(
            PARSER.parse("/api/v1/git/**"), PARSER.parse("/api/v1/applications/ssh-keypair/{branchedApplicationId}"));

    private static final PathPattern TEMPLATE_PATHS = PARSER.parse("/api/v1/app-templates/**");

    private static final PathPattern WORKSPACES = PARSER.parse("/api/v1/workspaces");

    private static final PathPattern WORKSPACE = PARSER.parse("/api/v1/workspaces/{workspaceId}");

    private final BuiltInGitConfig builtInGitConfig;

    public HiddenEndpointsWebFilter(BuiltInGitConfig builtInGitConfig) {
        this.builtInGitConfig = builtInGitConfig;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        return hiddenFeature(method, path, builtInGitConfig.isEnabled())
                .map(featureName -> refuse(exchange, featureName))
                .orElseGet(() -> chain.filter(exchange));
    }

    /**
     * @return the name of the hidden feature the request belongs to, or empty when the request may go through
     */
    static Optional<String> hiddenFeature(HttpMethod method, PathContainer path, boolean gitEnabled) {
        if (!gitEnabled && GIT_PATHS.stream().anyMatch(pattern -> pattern.matches(path))) {
            return Optional.of(BuiltInGitConfig.FEATURE_NAME);
        }
        if (TEMPLATE_PATHS.matches(path)) {
            return Optional.of(TEMPLATES_FEATURE_NAME);
        }
        if ((HttpMethod.POST.equals(method) && WORKSPACES.matches(path))
                || (HttpMethod.DELETE.equals(method) && WORKSPACE.matches(path))) {
            return Optional.of(WORKSPACES_FEATURE_NAME);
        }
        return Optional.empty();
    }

    private static Mono<Void> refuse(ServerWebExchange exchange, String featureName) {
        log.debug(
                "Refusing {} {}: {} is hidden in this fork",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                featureName);
        AppsmithError error = AppsmithError.FEATURE_DISABLED;
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(error.getHttpErrorCode()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"responseMeta\":{\"status\":" + error.getHttpErrorCode() + ",\"success\":false,\"error\":{"
                        + "\"code\":\"" + error.getAppErrorCode() + "\",\"title\":\"" + error.getTitle()
                        + "\",\"message\":\"" + error.getMessage(featureName) + "\"}},\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}

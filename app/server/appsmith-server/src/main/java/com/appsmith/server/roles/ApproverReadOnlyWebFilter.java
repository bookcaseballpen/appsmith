package com.appsmith.server.roles;

import com.appsmith.server.domains.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
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
 * Keeps the APPROVER role read-only. An approver holds manage permission on apps so the editor can load the
 * unpublished version for review, but every API call that changes something is refused here, except publishing,
 * running queries and the user's own session housekeeping.
 *
 * <p>Running queries ({@code POST /api/v1/actions/execute}) is allowed on purpose: an approver tests the unpublished
 * app in preview before publishing it, the same way users run the queries of published apps. It runs saved queries
 * against saved datasources; it does not change the app.
 *
 * <p>Fails closed: a new write endpoint is refused for approvers until it is added to {@link #APPROVER_WRITES}, and
 * a request is refused when the caller's role cannot be resolved. A few GET endpoints also change state (git pull,
 * git status and fetch, which write the repository; deploy key generation; the datasource OAuth callback); they are
 * listed in {@link #APPROVER_DENIED_READS}. GETs that only migrate a page's DSL or cache a datasource structure stay
 * open, because the editor needs them to load.
 *
 * <p>Ordered last, after Spring Security's {@code WebFilterChainProxy} (order -100), so the authenticated user is in
 * the security context when this filter runs.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApproverReadOnlyWebFilter implements WebFilter {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final Set<HttpMethod> READ_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private static final PathPattern API_PATHS = PARSER.parse("/api/**");

    private static final List<Rule> APPROVER_WRITES = List.of(
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/applications/publish/{applicationId}")),
            // Preview: see the class comment.
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/actions/execute")),
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/logout")),
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/usage-pulse")),
            // The user's own profile (name, preferences), not apps.
            new Rule(HttpMethod.PUT, PARSER.parse("/api/v1/users")));

    private static final List<Rule> APPROVER_DENIED_READS = List.of(
            new Rule(HttpMethod.GET, PARSER.parse("/api/v1/git/applications/{applicationId}/pull")),
            new Rule(HttpMethod.GET, PARSER.parse("/api/v1/git/applications/{applicationId}/status")),
            new Rule(HttpMethod.GET, PARSER.parse("/api/v1/git/applications/{applicationId}/fetch/remote")),
            new Rule(HttpMethod.GET, PARSER.parse("/api/v1/git/artifacts/{artifactId}/commits")),
            new Rule(HttpMethod.GET, PARSER.parse("/api/v1/git/artifacts/import/keys")),
            new Rule(HttpMethod.GET, PARSER.parse("/api/v1/datasources/authorize")));

    // Listing branches with pruneRefs=true fetches, checks out and can rewrite the default branch.
    private static final PathPattern GIT_REFS = PARSER.parse("/api/v1/git/applications/{applicationId}/refs");

    private final InstanceRoleService instanceRoleService;

    public ApproverReadOnlyWebFilter(InstanceRoleService instanceRoleService) {
        this.instanceRoleService = instanceRoleService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        boolean changesState = isDeniedRead(
                        method, path, exchange.getRequest().getQueryParams().getFirst("pruneRefs"))
                || (!READ_METHODS.contains(method) && API_PATHS.matches(path) && !isApproverWrite(method, path));
        if (!changesState) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(principal -> principal instanceof User)
                .flatMap(principal -> instanceRoleService.getRole((User) principal))
                .map(role -> role == InstanceRole.APPROVER)
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorResume(error -> {
                    log.warn(
                            "Refusing {} {}: could not resolve the caller's role",
                            method,
                            exchange.getRequest().getPath().value(),
                            error);
                    return Mono.just(Boolean.TRUE);
                })
                .flatMap(blocked -> blocked ? forbidden(exchange) : chain.filter(exchange));
    }

    static boolean isApproverWrite(HttpMethod method, PathContainer path) {
        return APPROVER_WRITES.stream().anyMatch(rule -> rule.matches(method, path));
    }

    static boolean isDeniedRead(HttpMethod method, PathContainer path, String pruneRefs) {
        if (HttpMethod.GET.equals(method) && GIT_REFS.matches(path)) {
            return "true".equalsIgnoreCase(pruneRefs);
        }
        return APPROVER_DENIED_READS.stream().anyMatch(rule -> rule.matches(method, path));
    }

    private static Mono<Void> forbidden(ServerWebExchange exchange) {
        log.debug(
                "Approver denied on {} {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"responseMeta\":{\"status\":403,\"success\":false,\"error\":{\"message\":"
                        + "\"Approvers can review and publish apps but not change them.\"}},\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private record Rule(HttpMethod method, PathPattern pattern) {
        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return method.equals(requestMethod) && pattern.matches(path);
        }
    }
}

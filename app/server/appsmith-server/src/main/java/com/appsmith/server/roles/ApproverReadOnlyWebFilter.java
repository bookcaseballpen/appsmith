package com.appsmith.server.roles;

import com.appsmith.server.domains.User;
import lombok.extern.slf4j.Slf4j;
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
 * running queries (preview) and the user's own session housekeeping.
 *
 * <p>Fails closed: a new write endpoint is refused for approvers until it is added to {@link #APPROVER_WRITES}.
 * Registered as a global {@link WebFilter}, so it runs after the security chain and sees the authenticated user.
 */
@Slf4j
@Component
public class ApproverReadOnlyWebFilter implements WebFilter {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final Set<HttpMethod> READ_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private static final PathPattern API_PATHS = PARSER.parse("/api/**");

    private static final List<Rule> APPROVER_WRITES = List.of(
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/applications/publish/{applicationId}")),
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/actions/execute")),
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/logout")),
            new Rule(HttpMethod.POST, PARSER.parse("/api/v1/usage-pulse")),
            // The user's own profile (name, preferences), not apps.
            new Rule(HttpMethod.PUT, PARSER.parse("/api/v1/users")));

    private final InstanceRoleService instanceRoleService;

    public ApproverReadOnlyWebFilter(InstanceRoleService instanceRoleService) {
        this.instanceRoleService = instanceRoleService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (READ_METHODS.contains(method) || !API_PATHS.matches(path) || isApproverWrite(method, path)) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(principal -> principal instanceof User)
                .flatMap(principal -> instanceRoleService.getRole((User) principal))
                .map(role -> role == InstanceRole.APPROVER)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(blocked -> blocked ? forbidden(exchange) : chain.filter(exchange));
    }

    static boolean isApproverWrite(HttpMethod method, PathContainer path) {
        return APPROVER_WRITES.stream().anyMatch(rule -> rule.matches(method, path));
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

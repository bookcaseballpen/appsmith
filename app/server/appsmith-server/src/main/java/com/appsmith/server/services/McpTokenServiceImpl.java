package com.appsmith.server.services;

import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.McpTokenResponseDTO;
import com.appsmith.server.helpers.McpTokenCache;
import com.appsmith.server.repositories.McpKeyRepository;
import com.appsmith.server.repositories.McpTokenRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ce.McpTokenServiceCEImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class McpTokenServiceImpl extends McpTokenServiceCEImpl implements McpTokenService {

    private static final String MCP_ACTION = "Use MCP";

    private final InstanceRoleService instanceRoleService;

    @Autowired
    public McpTokenServiceImpl(
            OrganizationService organizationService,
            UserRepository userRepository,
            AnalyticsService analyticsService,
            McpKeyRepository mcpKeyRepository,
            McpTokenRepository mcpTokenRepository,
            McpTokenCache mcpTokenCache,
            TransactionalOperator transactionalOperator,
            InstanceRoleService instanceRoleService) {
        super(
                organizationService,
                userRepository,
                analyticsService,
                mcpKeyRepository,
                mcpTokenRepository,
                mcpTokenCache,
                transactionalOperator);
        this.instanceRoleService = instanceRoleService;
    }

    // MCP lets an AI edit apps on the user's behalf, so only the roles that may edit can mint, rotate or use a token.

    @Override
    public Mono<McpTokenResponseDTO> create(User user, String name, Integer keySpanDays) {
        return requireEditRole(user).then(Mono.defer(() -> super.create(user, name, keySpanDays)));
    }

    @Override
    public Mono<McpTokenResponseDTO> rotate(User user, String keyId) {
        return requireEditRole(user).then(Mono.defer(() -> super.rotate(user, keyId)));
    }

    /**
     * A token stops working as soon as its owner loses the DEV or ADMIN role; it is rejected like an invalid token.
     */
    @Override
    public Mono<User> authenticate(String userKey) {
        return super.authenticate(userKey)
                .filterWhen(user -> instanceRoleService.getRole(user).map(InstanceRole::canEdit));
    }

    private Mono<Void> requireEditRole(User user) {
        return instanceRoleService.requireRole(user, MCP_ACTION, InstanceRole.ADMIN, InstanceRole.DEV);
    }
}

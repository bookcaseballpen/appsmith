package com.appsmith.server.configurations;

import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;

/**
 * Most upstream tests run as a seeded user that creates its own workspace first. In this fork only an ADMIN may
 * create a workspace, so those seeded users get the ADMIN role of the default workspace (not instance administration).
 * Runs after {@link SeedMongoData}, whose runner completes before the context is ready.
 */
@Slf4j
@Configuration
public class SeedTestUserRoles {

    // The users seeded by SeedMongoData; upstream tests create workspaces as each of them.
    static final String[] WORKSPACE_CREATORS = {
        "api_user", "usertest@usertest.com", "admin@solutiontest.com", "developer@solutiontest.com"
    };

    private final UserRepository userRepository;
    private final DefaultWorkspaceService defaultWorkspaceService;

    public SeedTestUserRoles(UserRepository userRepository, DefaultWorkspaceService defaultWorkspaceService) {
        this.userRepository = userRepository;
        this.defaultWorkspaceService = defaultWorkspaceService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void giveWorkspaceCreatorsTheAdminRole() {
        Flux.fromArray(WORKSPACE_CREATORS)
                .concatMap(userRepository::findByEmail)
                .concatMap(user -> defaultWorkspaceService.assignRole(user, InstanceRole.ADMIN))
                .doOnError(error -> log.error("Could not give the seeded test users the ADMIN role", error))
                .blockLast();
    }
}

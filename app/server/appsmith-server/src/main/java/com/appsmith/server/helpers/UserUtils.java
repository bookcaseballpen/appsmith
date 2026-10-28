package com.appsmith.server.helpers;

import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.domains.User;
import com.appsmith.server.helpers.ce.UserUtilsCE;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.ConfigRepository;
import com.appsmith.server.repositories.OrganizationRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.services.SessionUserService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class UserUtils extends UserUtilsCE {

    private final DefaultWorkspaceService defaultWorkspaceService;

    public UserUtils(
            ConfigRepository configRepository,
            PermissionGroupRepository permissionGroupRepository,
            CacheableRepositoryHelper cacheableRepositoryHelper,
            ObservationRegistry observationRegistry,
            CommonConfig commonConfig,
            OrganizationRepository organizationRepository,
            SessionUserService sessionUserService,
            InMemoryCacheableRepositoryHelper inMemoryCacheableRepositoryHelper,
            DefaultWorkspaceService defaultWorkspaceService) {
        super(
                configRepository,
                permissionGroupRepository,
                cacheableRepositoryHelper,
                observationRegistry,
                commonConfig,
                organizationRepository,
                sessionUserService,
                inMemoryCacheableRepositoryHelper);
        this.defaultWorkspaceService = defaultWorkspaceService;
    }

    /**
     * An instance administrator is the ADMIN role, so they also join the default workspace's administrator group.
     * This covers every path that makes administrators: the first signup, APPSMITH_ADMIN_EMAILS and the role API.
     */
    @Override
    public Mono<Boolean> makeInstanceAdministrator(List<User> users) {
        return super.makeInstanceAdministrator(users)
                .flatMap(result ->
                        assignDefaultWorkspaceRole(users, InstanceRole.ADMIN).thenReturn(result));
    }

    /**
     * A user who stops being an instance administrator falls back to the USER role.
     */
    @Override
    public Mono<Boolean> removeInstanceAdmin(List<User> users) {
        return super.removeInstanceAdmin(users).flatMap(result -> assignDefaultWorkspaceRole(users, InstanceRole.USER)
                .thenReturn(result));
    }

    private Mono<Void> assignDefaultWorkspaceRole(List<User> users, InstanceRole role) {
        return Flux.fromIterable(users)
                .concatMap(user -> defaultWorkspaceService.assignRole(user, role))
                .then();
    }
}

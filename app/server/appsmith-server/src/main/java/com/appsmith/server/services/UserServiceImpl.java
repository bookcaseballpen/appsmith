package com.appsmith.server.services;

import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.configurations.EmailConfig;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.UserProfileDTO;
import com.appsmith.server.helpers.SecureBaseUrlResolver;
import com.appsmith.server.helpers.UserServiceHelper;
import com.appsmith.server.helpers.UserUtils;
import com.appsmith.server.instanceconfigs.helpers.InstanceVariablesHelper;
import com.appsmith.server.notifications.EmailSender;
import com.appsmith.server.ratelimiting.RateLimitService;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.EmailVerificationTokenRepository;
import com.appsmith.server.repositories.PasswordResetTokenRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ce_compatible.UserServiceCECompatibleImpl;
import com.appsmith.server.solutions.PolicySolution;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static java.lang.Boolean.FALSE;

@Slf4j
@Service
public class UserServiceImpl extends UserServiceCECompatibleImpl implements UserService {

    private final InstanceRoleService instanceRoleService;
    private final DefaultWorkspaceService defaultWorkspaceService;

    public UserServiceImpl(
            Validator validator,
            UserRepository repository,
            WorkspaceService workspaceService,
            AnalyticsService analyticsService,
            SessionUserService sessionUserService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            EmailSender emailSender,
            ApplicationRepository applicationRepository,
            PolicySolution policySolution,
            CommonConfig commonConfig,
            EmailConfig emailConfig,
            UserDataService userDataService,
            OrganizationService organizationService,
            PermissionGroupService permissionGroupService,
            UserUtils userUtils,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            EmailService emailService,
            RateLimitService rateLimitService,
            PACConfigurationService pacConfigurationService,
            UserServiceHelper userServiceHelper,
            InstanceVariablesHelper instanceVariablesHelper,
            SecureBaseUrlResolver secureBaseUrlResolver,
            CacheablePylonHelper cacheablePylonHelper,
            InstanceRoleService instanceRoleService,
            DefaultWorkspaceService defaultWorkspaceService) {
        super(
                validator,
                repository,
                workspaceService,
                analyticsService,
                sessionUserService,
                passwordResetTokenRepository,
                passwordEncoder,
                commonConfig,
                userDataService,
                organizationService,
                userUtils,
                emailVerificationTokenRepository,
                emailService,
                rateLimitService,
                pacConfigurationService,
                userServiceHelper,
                instanceVariablesHelper,
                secureBaseUrlResolver,
                cacheablePylonHelper);
        this.instanceRoleService = instanceRoleService;
        this.defaultWorkspaceService = defaultWorkspaceService;
    }

    /**
     * New users never get a workspace of their own: they join the single default workspace with the USER role, and
     * an administrator changes it. A user who is already an instance administrator (first signup, APPSMITH_ADMIN_EMAILS)
     * was put in the administrator group by {@link UserUtils} and keeps it.
     */
    @Override
    protected Mono<Boolean> shouldCreateWorkspaceForUser(User user) {
        return instanceRoleService
                .getRole(user)
                .flatMap(role -> role == InstanceRole.ADMIN
                        ? Mono.<Void>empty()
                        : defaultWorkspaceService.assignRole(user, InstanceRole.USER))
                .onErrorResume(error -> {
                    log.error("Could not add user {} to the default workspace", user.getId(), error);
                    return Mono.empty();
                })
                .thenReturn(FALSE);
    }

    @Override
    public Mono<UserProfileDTO> buildUserProfileDTO(User user) {
        return super.buildUserProfileDTO(user).flatMap(profile -> {
            if (user.isAnonymous()) {
                return Mono.just(profile);
            }
            return findByEmail(user.getEmail())
                    .flatMap(instanceRoleService::getRole)
                    .defaultIfEmpty(InstanceRole.USER)
                    .zipWith(defaultWorkspaceService.getIds())
                    .map(tuple -> {
                        profile.setRole(tuple.getT1());
                        profile.setDefaultWorkspaceId(tuple.getT2().workspaceId());
                        return profile;
                    });
        });
    }
}

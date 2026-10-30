package com.appsmith.server.services;

import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.configurations.WithMockAppsmithUser;
import com.appsmith.server.domains.LoginSource;
import com.appsmith.server.domains.Organization;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.UserOrganizationHelper;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
public class UserServiceWithDisabledSignupTest {

    @Autowired
    UserService userService;

    @Autowired
    ApplicationService applicationService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    PermissionGroupRepository permissionGroupRepository;

    @Autowired
    UserOrganizationHelper userOrganizationHelper;

    @SpyBean
    CommonConfig commonConfig;

    @Autowired
    OrganizationService organizationService;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    Mono<User> userMono;

    @BeforeEach
    public void setup() {
        userMono = userService.findByEmail("usertest@usertest.com");
        Organization organization =
                organizationService.getCurrentUserOrganization().block();
        assert organization != null;
        organization.getOrganizationConfiguration().setIsSignupDisabled(true);
        organizationService.save(organization).block();
        Mockito.when(commonConfig.getAdminEmails())
                .thenReturn(Set.of("dummy_admin@appsmith.com", "dummy2@appsmith.com"));
    }

    @AfterAll
    public static void cleanup(@Autowired OrganizationService organizationService) {
        // Fork: upstream built a configuration with signup enabled but never set it, so signup stayed disabled for
        // the test classes after this one that share the database.
        Organization organization =
                organizationService.getCurrentUserOrganization().block();
        assert organization != null;
        organization.getOrganizationConfiguration().setIsSignupDisabled(false);
        organizationService.save(organization).block();
    }

    // Fork: an administrator gets no workspace of their own; they join the default workspace's administrator group.
    private Mono<Set<String>> defaultWorkspaceAdministrators() {
        return defaultWorkspaceService
                .getIds()
                .flatMap(ids -> permissionGroupRepository.findById(ids.groupId(InstanceRole.ADMIN)))
                .map(permissionGroup -> permissionGroup.getAssignedToUserIds());
    }

    @Test
    @WithMockAppsmithUser
    public void createNewUserValidWhenDisabled() {
        User newUser = new User();
        // Fork: a unique email; UserServiceTest creates new-user-email@email.com in the same database, and an
        // existing user is refused before the signup check.
        newUser.setEmail("new-user-" + UUID.randomUUID() + "@email.com");
        newUser.setPassword("new-user-test-password");

        Mono<User> userMono = userService.create(newUser);

        StepVerifier.create(userMono)
                .expectErrorMatches(error -> {
                    assertThat(error).isInstanceOf(AppsmithException.class);
                    assertThat(((AppsmithException) error).getError()).isEqualTo(AppsmithError.SIGNUP_DISABLED);
                    return true;
                })
                .verify();
    }

    @Test
    @WithMockAppsmithUser
    public void createNewAdminValidWhenDisabled() {
        User newUser = new User();
        newUser.setEmail("dummy_admin@appsmith.com");
        newUser.setPassword("admin-password");

        Mono<User> userMono = userService.create(newUser).cache();

        Mono<Set<String>> assignedToUsersMono = userMono.then(defaultWorkspaceAdministrators());

        StepVerifier.create(Mono.zip(userMono, assignedToUsersMono))
                .assertNext(tuple -> {
                    User user = tuple.getT1();
                    Set<String> workspaceAssignedToUsers = tuple.getT2();
                    assertThat(user).isNotNull();
                    assertThat(user.getId()).isNotNull();
                    assertThat(user.getEmail()).isEqualTo("dummy_admin@appsmith.com");
                    assertThat(user.getName()).isNullOrEmpty();
                    assertThat(user.getPolicies()).isNotEmpty();
                    assertThat(workspaceAssignedToUsers).contains(user.getId());
                })
                .verifyComplete();
    }

    @Test
    @WithMockAppsmithUser
    public void createNewAdminValidWhenDisabled2() {
        User newUser = new User();
        newUser.setEmail("dummy2@appsmith.com");
        newUser.setPassword("admin-password");

        Mono<User> userMono = userService.create(newUser).cache();

        Mono<Set<String>> assignedToUsersMono = userMono.then(defaultWorkspaceAdministrators());

        StepVerifier.create(Mono.zip(userMono, assignedToUsersMono))
                .assertNext(tuple -> {
                    User user = tuple.getT1();
                    Set<String> workspaceAssignedToUsers = tuple.getT2();
                    assertThat(user).isNotNull();
                    assertThat(user.getId()).isNotNull();
                    assertThat(user.getEmail()).isEqualTo("dummy2@appsmith.com");
                    assertThat(user.getName()).isNullOrEmpty();
                    assertThat(user.getPolicies()).isNotEmpty();

                    assertThat(workspaceAssignedToUsers).contains(user.getId());
                })
                .verifyComplete();
    }

    @Test
    @WithMockAppsmithUser
    public void signUpViaFormLoginIfAlreadyInvited() {
        String organizationId =
                userOrganizationHelper.getCurrentUserOrganizationId().block();

        User newUser = new User();
        newUser.setEmail("alreadyInvited@alreadyInvited.com");
        newUser.setIsEnabled(false);
        newUser.setOrganizationId(organizationId);

        userRepository.save(newUser).block();

        User signupUser = new User();
        signupUser.setEmail(newUser.getEmail());
        signupUser.setPassword("password");
        signupUser.setSource(LoginSource.FORM);

        Mono<User> userMono = userService.create(signupUser);

        StepVerifier.create(userMono)
                .assertNext(user -> {
                    assertThat(user.getEmail()).isEqualTo(newUser.getEmail());
                    assertThat(user.getSource()).isEqualTo(LoginSource.FORM);
                    assertThat(user.getIsEnabled()).isTrue();
                })
                .verifyComplete();
    }
}

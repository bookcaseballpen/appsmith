package com.appsmith.server.grants;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ApplicationPageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Teams: who may manage them, their members, and that membership changes take effect at once.
 */
@SpringBootTest
@DirtiesContext
class TeamServiceTest {

    @Autowired
    ReactiveWebApplicationContext context;

    @Autowired
    TeamService teamService;

    @Autowired
    AppGrantService appGrantService;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PermissionGroupRepository permissionGroupRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    @Autowired
    ApplicationPageService applicationPageService;

    @Autowired
    ApplicationService applicationService;

    @Autowired
    ApplicationRepository applicationRepository;

    WebTestClient webTestClient;
    Map<InstanceRole, User> users;
    String workspaceId;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();
        workspaceId = defaultWorkspaceService.getIds().block().workspaceId();
        users = new EnumMap<>(InstanceRole.class);
        for (InstanceRole role : InstanceRole.values()) {
            users.put(role, createUserWithRole(role));
        }
    }

    @Test
    void should_createRenameAndListTeam_when_adminManagesTeams() {
        // Given
        TeamDTO created = asAdmin(teamService.create(new TeamDTO.Request("  " + unique("Sales") + "  ", "first")));

        // When
        String newName = unique("Field sales");
        TeamDTO renamed = asAdmin(teamService.update(created.id(), new TeamDTO.Request(newName, "second")));
        List<TeamDTO> teams = asAdmin(teamService.list());

        // Then
        assertThat(created.name()).doesNotStartWith(" ").doesNotEndWith(" ");
        assertThat(renamed.name()).isEqualTo(newName);
        assertThat(renamed.description()).isEqualTo("second");
        assertThat(teams).extracting(TeamDTO::id).contains(created.id());
    }

    @Test
    void should_refuseTeam_when_nameIsTakenIgnoringCase() {
        // Given
        String name = unique("Finance");
        asAdmin(teamService.create(new TeamDTO.Request(name, null)));

        // When
        Optional<AppsmithError> error =
                errorOf(teamService.create(new TeamDTO.Request(name.toUpperCase(), null)), InstanceRole.ADMIN);

        // Then
        assertThat(error).contains(AppsmithError.DUPLICATE_KEY_USER_ERROR);
    }

    @Test
    void should_keepAtMostOneTeam_when_teamsWithSameNameAreCreatedAtOnce() {
        // Given
        String name = unique("Rush");

        // When
        List<Optional<AppsmithError>> outcomes = Flux.range(0, 8)
                .flatMap(i -> runAs(
                                teamService
                                        .create(new TeamDTO.Request(name, null))
                                        .map(created -> Optional.<AppsmithError>empty())
                                        .onErrorResume(
                                                AppsmithException.class,
                                                exception -> Mono.just(Optional.of(exception.getError()))),
                                users.get(InstanceRole.ADMIN))
                        .subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        // Then
        long liveTeams = permissionGroupRepository
                .queryBuilder()
                .criteria(Bridge.<PermissionGroup>equal(PermissionGroup.Fields.defaultDomainType, GrantGroups.TEAM)
                        .equalIgnoreCase(PermissionGroup.Fields.name, name))
                .all()
                .count()
                .block();
        assertThat(liveTeams).isLessThanOrEqualTo(1);
        assertThat(outcomes.stream().filter(Optional::isEmpty).count()).isEqualTo(liveTeams);
        assertThat(outcomes.stream().flatMap(Optional::stream)).containsOnly(AppsmithError.DUPLICATE_KEY_USER_ERROR);
    }

    @Test
    void should_revokeLeftoverGrants_when_deleteIsRetriedAfterTeamWasArchived() {
        // Given
        User dev = users.get(InstanceRole.DEV);
        TeamDTO team = asAdmin(teamService.create(new TeamDTO.Request(unique("Half gone"), null)));
        asAdmin(teamService.addMember(team.id(), dev.getId()));
        Application application = createAppAsAdmin();
        asAdmin(appGrantService.setGrant(
                application.getId(),
                new AppGrantDTO.Request(new AppGrantDTO.Grantee(AppGrantDTO.Type.TEAM, team.id()), GrantLevel.VIEW)));
        // A first delete that stopped after archiving the team, before revoking its grants.
        permissionGroupRepository.archiveById(team.id()).block();

        // When
        Optional<AppsmithError> error = errorOf(teamService.delete(team.id()).thenReturn(true), InstanceRole.ADMIN);

        // Then
        assertThat(error).isEmpty();
        assertThat(applicationRepository
                        .findById(application.getId())
                        .block()
                        .getPolicyMap()
                        .values())
                .allSatisfy(policy -> assertThat(policy.getPermissionGroups()).doesNotContain(team.id()));
        assertThat(reads(dev, application.getId())).isFalse();
    }

    @Test
    void should_refuseDeleteAndKeepGroup_when_idIsNotATeam() {
        // Given
        String roleGroupId = defaultWorkspaceService.getIds().block().groupId(InstanceRole.DEV);

        // When
        Optional<AppsmithError> error = errorOf(teamService.delete(roleGroupId).thenReturn(true), InstanceRole.ADMIN);

        // Then
        assertThat(error).contains(AppsmithError.NO_RESOURCE_FOUND);
        assertThat(permissionGroupRepository.findById(roleGroupId).block()).isNotNull();
    }

    @Test
    void should_refuseTeam_when_nameIsBlank() {
        // When
        Optional<AppsmithError> error =
                errorOf(teamService.create(new TeamDTO.Request("   ", null)), InstanceRole.ADMIN);

        // Then
        assertThat(error).contains(AppsmithError.INVALID_PARAMETER);
    }

    @Test
    void should_changeAccessAtOnce_when_memberIsAddedAndRemoved() {
        // Given
        User dev = users.get(InstanceRole.DEV);
        TeamDTO team = asAdmin(teamService.create(new TeamDTO.Request(unique("Builders"), null)));
        Application application = createAppAsAdmin();
        asAdmin(appGrantService.setGrant(
                application.getId(),
                new AppGrantDTO.Request(new AppGrantDTO.Grantee(AppGrantDTO.Type.TEAM, team.id()), GrantLevel.EDIT)));
        // Resolves and caches the developer's groups before they join.
        boolean seesBeforeJoining = reads(dev, application.getId());

        // When
        TeamDTO withMember = asAdmin(teamService.addMember(team.id(), dev.getId()));
        boolean seesAsMember = reads(dev, application.getId());
        TeamDTO withoutMember = asAdmin(teamService.removeMember(team.id(), dev.getId()));
        boolean seesAfterLeaving = reads(dev, application.getId());

        // Then
        assertThat(withMember.members()).extracting(TeamDTO.Member::id).containsExactly(dev.getId());
        assertThat(withoutMember.members()).isEmpty();
        assertThat(seesBeforeJoining).isFalse();
        assertThat(seesAsMember).isTrue();
        assertThat(seesAfterLeaving).isFalse();
    }

    @Test
    void should_revokeGrantsAndRemoveTeam_when_adminDeletesTeam() {
        // Given
        User dev = users.get(InstanceRole.DEV);
        TeamDTO team = asAdmin(teamService.create(new TeamDTO.Request(unique("Temp"), null)));
        asAdmin(teamService.addMember(team.id(), dev.getId()));
        Application application = createAppAsAdmin();
        asAdmin(appGrantService.setGrant(
                application.getId(),
                new AppGrantDTO.Request(new AppGrantDTO.Grantee(AppGrantDTO.Type.TEAM, team.id()), GrantLevel.VIEW)));
        boolean seesBeforeDelete = reads(dev, application.getId());

        // When
        asAdmin(teamService.delete(team.id()).thenReturn(true));

        // Then
        assertThat(seesBeforeDelete).isTrue();
        assertThat(reads(dev, application.getId())).isFalse();
        assertThat(asAdmin(appGrantService.getGrants(application.getId()))).isEmpty();
        assertThat(errorOf(teamService.get(team.id()), InstanceRole.ADMIN)).contains(AppsmithError.NO_RESOURCE_FOUND);
    }

    @Test
    void should_refuseMember_when_userDoesNotExist() {
        // Given
        TeamDTO team = asAdmin(teamService.create(new TeamDTO.Request(unique("Nobody"), null)));

        // When
        Optional<AppsmithError> error = errorOf(teamService.addMember(team.id(), "no-such-user"), InstanceRole.ADMIN);

        // Then
        assertThat(error).contains(AppsmithError.NO_RESOURCE_FOUND);
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"DEV", "APPROVER", "USER"})
    void should_refuseTeamApi_when_callerIsNotAdmin(InstanceRole role) {
        // Given
        TeamDTO team = asAdmin(teamService.create(new TeamDTO.Request(unique("Locked"), null)));

        // When / Then
        clientFor(role).get().uri("/api/v1/teams").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        clientFor(role)
                .post()
                .uri("/api/v1/teams")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", unique("Mine")))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
        clientFor(role)
                .put()
                .uri(
                        "/api/v1/teams/{id}/members/{userId}",
                        team.id(),
                        users.get(role).getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
        clientFor(role)
                .delete()
                .uri("/api/v1/teams/{id}", team.id())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asAdmin(teamService.get(team.id())).members()).isEmpty();
    }

    @Test
    void should_manageTeamOverHttp_when_callerIsAdmin() {
        // Given
        User dev = users.get(InstanceRole.DEV);

        // When / Then
        Map<?, ?> created = clientFor(InstanceRole.ADMIN)
                .post()
                .uri("/api/v1/teams")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", unique("Http team")))
                .exchange()
                .expectStatus()
                .is2xxSuccessful()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        String teamId = (String) ((Map<?, ?>) created.get("data")).get("id");
        clientFor(InstanceRole.ADMIN)
                .put()
                .uri("/api/v1/teams/{id}/members/{userId}", teamId, dev.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.members[0].email")
                .isEqualTo(dev.getEmail());
        clientFor(InstanceRole.ADMIN)
                .delete()
                .uri("/api/v1/teams/{id}/members/{userId}", teamId, dev.getId())
                .exchange()
                .expectStatus()
                .isOk();
        clientFor(InstanceRole.ADMIN)
                .delete()
                .uri("/api/v1/teams/{id}", teamId)
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(permissionGroupRepository.findById(teamId).block()).isNull();
    }

    private Application createAppAsAdmin() {
        Application unsaved = new Application();
        unsaved.setName(unique("team-app"));
        return asAdmin(applicationPageService.createApplication(unsaved, workspaceId));
    }

    private boolean reads(User user, String applicationId) {
        return runAs(
                        applicationService
                                .findByWorkspaceId(workspaceId, AclPermission.READ_APPLICATIONS)
                                .map(Application::getId)
                                .collectList(),
                        user)
                .block()
                .contains(applicationId);
    }

    private <T> T asAdmin(Mono<T> call) {
        return runAs(call, users.get(InstanceRole.ADMIN)).block();
    }

    private <T> Optional<AppsmithError> errorOf(Mono<T> call, InstanceRole role) {
        return runAs(
                        call.map(result -> Optional.<AppsmithError>empty())
                                .onErrorResume(
                                        AppsmithException.class,
                                        exception -> Mono.just(Optional.of(exception.getError()))),
                        users.get(role))
                .block();
    }

    private User createUserWithRole(InstanceRole role) {
        User user = new User();
        user.setEmail(unique(role.name().toLowerCase()) + "@teams.example.com");
        user.setName(role.name());
        user.setIsEnabled(true);
        user.setOrganizationId(
                cacheableRepositoryHelper.getCurrentUserOrganizationId().block());
        User saved = userRepository.save(user).block();
        instanceRoleService.setRole(saved, role).block();
        return saved;
    }

    private WebTestClient clientFor(InstanceRole role) {
        User user = users.get(role);
        return webTestClient
                .mutateWith(mockAuthentication(
                        new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities())))
                .mutateWith(csrf());
    }

    private static <T> Mono<T> runAs(Mono<T> publisher, User user) {
        return publisher.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}

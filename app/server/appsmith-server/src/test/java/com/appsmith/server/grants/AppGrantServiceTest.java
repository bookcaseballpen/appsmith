package com.appsmith.server.grants;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionDTO;
import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStorageDTO;
import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.constants.ArtifactType;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.datasources.base.DatasourceService;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.PageDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.exports.internal.ExportService;
import com.appsmith.server.helpers.MockPluginExecutor;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.roles.PublishApproval;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.LayoutActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Per-app grants to teams and single users, through the real services, policies and permission checks.
 */
@SpringBootTest
@DirtiesContext
class AppGrantServiceTest {

    @Autowired
    ReactiveWebApplicationContext context;

    @Autowired
    AppGrantService appGrantService;

    @Autowired
    TeamService teamService;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    @Autowired
    ApplicationPageService applicationPageService;

    @Autowired
    ApplicationService applicationService;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    NewPageRepository newPageRepository;

    @Autowired
    NewActionRepository newActionRepository;

    @Autowired
    PermissionGroupRepository permissionGroupRepository;

    @Autowired
    LayoutActionService layoutActionService;

    @Autowired
    DatasourceService datasourceService;

    @Autowired
    PluginService pluginService;

    @Autowired
    ExportService exportService;

    @Autowired
    ImportService importService;

    @MockBean
    PluginExecutorHelper pluginExecutorHelper;

    WebTestClient webTestClient;
    String workspaceId;
    User admin;
    User dev;
    User otherDev;
    User approver;
    User viewer;
    Application application;
    String pageId;
    Datasource datasource;

    @BeforeEach
    void setUp() {
        Mockito.when(pluginExecutorHelper.getPluginExecutor(Mockito.any()))
                .thenReturn(Mono.just(new MockPluginExecutor()));
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();
        workspaceId = defaultWorkspaceService.getIds().block().workspaceId();
        admin = createUserWithRole(InstanceRole.ADMIN);
        dev = createUserWithRole(InstanceRole.DEV);
        otherDev = createUserWithRole(InstanceRole.DEV);
        approver = createUserWithRole(InstanceRole.APPROVER);
        viewer = createUserWithRole(InstanceRole.USER);

        Application unsaved = new Application();
        unsaved.setName(unique("grants-app"));
        application = runAs(applicationPageService.createApplication(unsaved, workspaceId), admin)
                .block();
        pageId = application.getPages().get(0).getId();

        Plugin plugin = pluginService.findByPackageName("restapi-plugin").block();
        HashMap<String, DatasourceStorageDTO> storages = new HashMap<>();
        DatasourceConfiguration configuration = new DatasourceConfiguration();
        configuration.setUrl("https://grants.example.com");
        storages.put(
                FieldName.UNUSED_ENVIRONMENT_ID,
                new DatasourceStorageDTO(null, FieldName.UNUSED_ENVIRONMENT_ID, configuration));
        Datasource unsavedDatasource = new Datasource();
        unsavedDatasource.setName(unique("grants-datasource"));
        unsavedDatasource.setWorkspaceId(workspaceId);
        unsavedDatasource.setPluginId(plugin.getId());
        unsavedDatasource.setDatasourceStorages(storages);
        datasource = runAs(datasourceService.create(unsavedDatasource), admin).block();
    }

    @Test
    void should_letTeamMembersEdit_when_teamHoldsEditGrant() {
        // Given
        TeamDTO team = createTeam(dev);

        // When
        grant(AppGrantDTO.Type.TEAM, team.id(), GrantLevel.EDIT);

        // Then
        assertThat(canRead(dev)).isTrue();
        assertThat(canAddPage(dev)).isTrue();
        assertThat(canRead(otherDev)).isFalse();
        assertThat(canAddPage(otherDev)).isFalse();
    }

    @Test
    void should_letUserRead_when_userHoldsEditGrantThroughTeamAndAppIsLive() {
        // Given
        TeamDTO team = createTeam(viewer);
        grant(AppGrantDTO.Type.TEAM, team.id(), GrantLevel.EDIT);
        makeLive();
        String nameBefore =
                applicationRepository.findById(application.getId()).block().getName();

        // When
        boolean addsPage = canAddPage(viewer);
        Application renamed = new Application();
        renamed.setName(unique("renamed-by-user"));
        runAs(applicationService.update(application.getId(), renamed), viewer)
                .onErrorResume(error -> Mono.empty())
                .block();

        // Then
        assertThat(canRead(viewer)).isTrue();
        assertThat(addsPage).isFalse();
        assertThat(applicationRepository.findById(application.getId()).block().getName())
                .isEqualTo(nameBefore);
    }

    @Test
    void should_keepUserFromEditing_when_userJoinsEditTeamAfterGateCachedTheirGroups() {
        // Given
        grant(AppGrantDTO.Type.USER, viewer.getId(), GrantLevel.VIEW);
        makeLive();
        // Resolves and caches the user's groups and grant groups before they join the team.
        boolean addsPageBeforeJoining = canAddPage(viewer);
        TeamDTO team = createTeam();
        grant(AppGrantDTO.Type.TEAM, team.id(), GrantLevel.EDIT);

        // When
        runAs(teamService.addMember(team.id(), viewer.getId()), admin).block();

        // Then
        assertThat(addsPageBeforeJoining).isFalse();
        assertThat(canRead(viewer)).isTrue();
        assertThat(canAddPage(viewer)).isFalse();
        assertThat(cacheableRepositoryHelper.getGrantGroupsOfUser(viewer).block())
                .contains(team.id(), personalGroupId(viewer));
    }

    @Test
    void should_importWithSuffixedNameAndKeepOtherAppHidden_when_devImportsAppNamedLikeHiddenApp() {
        // Given
        ApplicationJson export = runAs(
                        exportService
                                .exportByArtifactIdAndBranchName(application.getId(), "", ArtifactType.APPLICATION)
                                .map(json -> (ApplicationJson) json),
                        admin)
                .block();
        boolean seesTakenNameBefore = canRead(dev);

        // When
        Application imported = runAs(
                        importService
                                .importNewArtifactInWorkspaceFromJson(workspaceId, export)
                                .map(artifact -> (Application) artifact),
                        dev)
                .block();

        // Then
        assertThat(seesTakenNameBefore).isFalse();
        assertThat(imported.getId()).isNotEqualTo(application.getId());
        assertThat(imported.getName()).isEqualTo(application.getName() + " (1)");
        assertThat(canRead(dev, imported.getId())).isTrue();
        assertThat(canRead(dev)).isFalse();
        assertThat(applicationRepository.findById(application.getId()).block().getName())
                .isEqualTo(application.getName());
    }

    @Test
    void should_letDevReadButNotEdit_when_devHoldsViewGrant() {
        // When
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.VIEW);

        // Then
        assertThat(canRead(dev)).isTrue();
        assertThat(canAddPage(dev)).isFalse();
    }

    @Test
    void should_showOnlyLiveApp_when_userHoldsViewGrant() {
        // Given
        grant(AppGrantDTO.Type.USER, viewer.getId(), GrantLevel.VIEW);
        boolean readsBeforeLive = canRead(viewer);

        // When
        makeLive();

        // Then
        assertThat(readsBeforeLive).isFalse();
        assertThat(canRead(viewer)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"DEV", "USER"})
    void should_seeNoApp_when_roleHoldsNoGrant(InstanceRole role) {
        // Given
        makeLive();
        User user = role == InstanceRole.DEV ? dev : viewer;

        // When / Then
        assertThat(canRead(user)).isFalse();
        assertThat(canAddPage(user)).isFalse();
    }

    @Test
    void should_keepSeeingEveryApp_when_callerIsApprover() {
        // When / Then
        assertThat(canRead(approver)).isTrue();
    }

    @Test
    void should_takeAccessAway_when_grantIsRevoked() {
        // Given
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.EDIT);

        // When
        List<AppGrantDTO> grants = grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.NONE);

        // Then
        assertThat(grants).isEmpty();
        assertThat(canRead(dev)).isFalse();
        assertThat(canAddPage(dev)).isFalse();
    }

    @Test
    void should_takeEditAway_when_editGrantIsLoweredToView() {
        // Given
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.EDIT);

        // When
        List<AppGrantDTO> grants = grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.VIEW);

        // Then
        assertThat(grants).singleElement().satisfies(grant -> assertThat(grant.level())
                .isEqualTo(GrantLevel.VIEW));
        assertThat(canRead(dev)).isTrue();
        assertThat(canAddPage(dev)).isFalse();
        assertThat(groupsWith(newPageRepository.findById(pageId).block().getPolicyMap(), "manage:pages"))
                .doesNotContain(personalGroupId(dev));
    }

    @Test
    void should_grantPagesAndQueries_when_appWithQueryIsGranted() {
        // Given
        String actionId = createQuery(admin, pageId).getId();
        TeamDTO team = createTeam(otherDev);

        // When
        grant(AppGrantDTO.Type.TEAM, team.id(), GrantLevel.VIEW);
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.EDIT);

        // Then
        String devGroup = personalGroupId(dev);
        Map<String, Policy> page = newPageRepository.findById(pageId).block().getPolicyMap();
        assertThat(groupsWith(page, "read:pages")).contains(team.id(), devGroup);
        assertThat(groupsWith(page, "manage:pages")).contains(devGroup).doesNotContain(team.id());
        Map<String, Policy> action =
                newActionRepository.findById(actionId).block().getPolicyMap();
        assertThat(groupsWith(action, "execute:actions")).contains(team.id(), devGroup);
        assertThat(groupsWith(action, "manage:actions")).contains(devGroup).doesNotContain(team.id());
        assertThat(groupsWith(application(), "publish:applications")).doesNotContain(team.id(), devGroup);
    }

    @Test
    void should_inheritGrant_when_pageAndQueryAreCreatedAfterGrant() {
        // Given
        TeamDTO viewers = createTeam(otherDev);
        grant(AppGrantDTO.Type.TEAM, viewers.id(), GrantLevel.VIEW);
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.EDIT);

        // When
        PageDTO page = newPage();
        PageDTO createdPage =
                runAs(applicationPageService.createPage(page), dev).block();
        ActionDTO query = createQuery(dev, createdPage.getId());

        // Then
        String devGroup = personalGroupId(dev);
        NewPage storedPage = newPageRepository.findById(createdPage.getId()).block();
        assertThat(groupsWith(storedPage.getPolicyMap(), "manage:pages")).contains(devGroup);
        assertThat(groupsWith(storedPage.getPolicyMap(), "read:pages")).contains(viewers.id());
        NewAction storedQuery = newActionRepository.findById(query.getId()).block();
        assertThat(groupsWith(storedQuery.getPolicyMap(), "manage:actions")).contains(devGroup);
        assertThat(groupsWith(storedQuery.getPolicyMap(), "execute:actions")).contains(viewers.id());
    }

    @Test
    void should_giveCreatorEditGrant_when_devCreatesApp() {
        // Given
        Application unsaved = new Application();
        unsaved.setName(unique("dev-app"));

        // When
        Application created = runAs(applicationPageService.createApplication(unsaved, workspaceId), dev)
                .block();

        // Then
        assertThat(runAs(appGrantService.getGrants(created.getId()), admin).block())
                .singleElement()
                .satisfies(grant -> {
                    assertThat(grant.grantee()).isEqualTo(new AppGrantDTO.Grantee(AppGrantDTO.Type.USER, dev.getId()));
                    assertThat(grant.level()).isEqualTo(GrantLevel.EDIT);
                });
        assertThat(canAddPage(dev, created.getId())).isTrue();
        assertThat(canAddPage(otherDev, created.getId())).isFalse();
    }

    @Test
    void should_giveCreatorEditGrant_when_devDuplicatesGrantedApp() {
        // Given
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.EDIT);

        // When
        Application copy = runAs(applicationPageService.cloneApplication(application.getId()), dev)
                .block();

        // Then
        assertThat(canAddPage(dev, copy.getId())).isTrue();
        assertThat(canRead(otherDev, copy.getId())).isFalse();
    }

    @Test
    void should_keepGrants_when_devImportsOverGrantedApp() {
        // Given
        TeamDTO viewers = createTeam(otherDev);
        grant(AppGrantDTO.Type.TEAM, viewers.id(), GrantLevel.VIEW);
        grant(AppGrantDTO.Type.USER, dev.getId(), GrantLevel.EDIT);
        ApplicationJson export = runAs(
                        exportService
                                .exportByArtifactIdAndBranchName(application.getId(), "", ArtifactType.APPLICATION)
                                .map(json -> (ApplicationJson) json),
                        admin)
                .block();

        // When
        runAs(importService.updateNonGitConnectedArtifactFromJson(workspaceId, application.getId(), export), dev)
                .block();

        // Then
        assertThat(runAs(appGrantService.getGrants(application.getId()), admin).block())
                .extracting(AppGrantDTO::grantee, AppGrantDTO::level)
                .containsExactlyInAnyOrder(
                        tuple(new AppGrantDTO.Grantee(AppGrantDTO.Type.TEAM, viewers.id()), GrantLevel.VIEW),
                        tuple(new AppGrantDTO.Grantee(AppGrantDTO.Type.USER, dev.getId()), GrantLevel.EDIT));
        assertThat(canAddPage(dev)).isTrue();
        assertThat(canRead(otherDev)).isTrue();
        assertThat(canAddPage(otherDev)).isFalse();
    }

    @Test
    void should_refuseImportOverApp_when_devHoldsNoGrant() {
        // Given
        ApplicationJson export = runAs(
                        exportService
                                .exportByArtifactIdAndBranchName(application.getId(), "", ArtifactType.APPLICATION)
                                .map(json -> (ApplicationJson) json),
                        admin)
                .block();

        // When
        Optional<AppsmithError> error = errorOf(
                importService.updateNonGitConnectedArtifactFromJson(workspaceId, application.getId(), export), dev);

        // Then
        assertThat(error).isPresent();
        assertThat(canRead(dev)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = InstanceRole.class,
            names = {"DEV", "APPROVER", "USER"})
    void should_refuseGrantApi_when_callerIsNotAdmin(InstanceRole role) {
        // Given
        User caller = Map.of(InstanceRole.DEV, dev, InstanceRole.APPROVER, approver, InstanceRole.USER, viewer)
                .get(role);

        // When / Then
        clientFor(caller)
                .get()
                .uri("/api/v1/applications/{id}/grants", application.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
        clientFor(caller)
                .put()
                .uri("/api/v1/applications/{id}/grants", application.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("grantee", Map.of("type", "USER", "id", caller.getId()), "level", "EDIT"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(canRead(caller)).isEqualTo(role == InstanceRole.APPROVER);
    }

    @Test
    void should_setAndListGrant_when_adminCallsGrantApi() {
        // When / Then
        clientFor(admin)
                .put()
                .uri("/api/v1/applications/{id}/grants", application.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("grantee", Map.of("type", "USER", "id", dev.getId()), "level", "EDIT"))
                .exchange()
                .expectStatus()
                .isOk();
        clientFor(admin)
                .get()
                .uri("/api/v1/applications/{id}/grants", application.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].grantee.type")
                .isEqualTo("USER")
                .jsonPath("$.data[0].grantee.id")
                .isEqualTo(dev.getId())
                .jsonPath("$.data[0].name")
                .isEqualTo(dev.getEmail())
                .jsonPath("$.data[0].level")
                .isEqualTo("EDIT");
        assertThat(canAddPage(dev)).isTrue();
    }

    @Test
    void should_refuseGrant_when_teamDoesNotExist() {
        // When
        Optional<AppsmithError> error = errorOf(
                appGrantService.setGrant(
                        application.getId(),
                        new AppGrantDTO.Request(
                                new AppGrantDTO.Grantee(AppGrantDTO.Type.TEAM, "no-such-team"), GrantLevel.VIEW)),
                admin);

        // Then
        assertThat(error).contains(AppsmithError.NO_RESOURCE_FOUND);
    }

    private List<AppGrantDTO> grant(AppGrantDTO.Type type, String id, GrantLevel level) {
        return runAs(
                        appGrantService.setGrant(
                                application.getId(), new AppGrantDTO.Request(new AppGrantDTO.Grantee(type, id), level)),
                        admin)
                .block();
    }

    private TeamDTO createTeam(User... members) {
        TeamDTO team = runAs(teamService.create(new TeamDTO.Request(unique("team"), null)), admin)
                .block();
        for (User member : members) {
            runAs(teamService.addMember(team.id(), member.getId()), admin).block();
        }
        return team;
    }

    private void makeLive() {
        runAs(PublishApproval.asApprovedRelease(applicationPageService.publish(application.getId(), true)), admin)
                .block();
    }

    private Map<String, Policy> application() {
        return applicationRepository.findById(application.getId()).block().getPolicyMap();
    }

    private String personalGroupId(User user) {
        return permissionGroupRepository
                .findByDefaultDomainIdAndDefaultDomainType(user.getId(), GrantGroups.USER_GRANT)
                .map(PermissionGroup::getId)
                .blockFirst();
    }

    private boolean canRead(User user) {
        return canRead(user, application.getId());
    }

    private boolean canRead(User user, String applicationId) {
        List<String> ids = runAs(
                        applicationService
                                .findByWorkspaceId(workspaceId, AclPermission.READ_APPLICATIONS)
                                .map(Application::getId)
                                .collectList(),
                        user)
                .block();
        return ids.contains(applicationId);
    }

    private boolean canAddPage(User user) {
        return canAddPage(user, application.getId());
    }

    private boolean canAddPage(User user, String applicationId) {
        PageDTO page = newPage();
        page.setApplicationId(applicationId);
        return errorOf(applicationPageService.createPage(page), user).isEmpty();
    }

    private PageDTO newPage() {
        PageDTO page = new PageDTO();
        page.setName("Page" + UUID.randomUUID().toString().substring(0, 8));
        page.setApplicationId(application.getId());
        return page;
    }

    private ActionDTO createQuery(User user, String targetPageId) {
        ActionDTO action = new ActionDTO();
        action.setName("Query" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        action.setPageId(targetPageId);
        Datasource reference = new Datasource();
        reference.setId(datasource.getId());
        reference.setPluginId(datasource.getPluginId());
        action.setDatasource(reference);
        ActionConfiguration actionConfiguration = new ActionConfiguration();
        actionConfiguration.setPath("/users");
        action.setActionConfiguration(actionConfiguration);
        return runAs(layoutActionService.createSingleAction(action), user).block();
    }

    private static Set<String> groupsWith(Map<String, Policy> policyMap, String permission) {
        Policy policy = policyMap.get(permission);
        return policy == null || policy.getPermissionGroups() == null ? Set.of() : policy.getPermissionGroups();
    }

    private static <T> Optional<AppsmithError> errorOf(Mono<T> call, User user) {
        return runAs(
                        call.map(result -> Optional.<AppsmithError>empty())
                                .defaultIfEmpty(Optional.of(AppsmithError.NO_RESOURCE_FOUND))
                                .onErrorResume(
                                        AppsmithException.class,
                                        exception -> Mono.just(Optional.of(exception.getError()))),
                        user)
                .block();
    }

    private User createUserWithRole(InstanceRole role) {
        User user = new User();
        user.setEmail(unique(role.name().toLowerCase()) + "@grants.example.com");
        user.setName(role.name());
        user.setIsEnabled(true);
        user.setOrganizationId(
                cacheableRepositoryHelper.getCurrentUserOrganizationId().block());
        User saved = userRepository.save(user).block();
        instanceRoleService.setRole(saved, role).block();
        return saved;
    }

    private WebTestClient clientFor(User user) {
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

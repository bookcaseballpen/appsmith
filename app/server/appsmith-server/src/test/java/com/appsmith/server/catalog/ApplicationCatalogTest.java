package com.appsmith.server.catalog;

import com.appsmith.server.approvals.ApplicationApprovalService;
import com.appsmith.server.approvals.ApprovalFingerprint;
import com.appsmith.server.approvals.ApprovalStatus;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.grants.AppGrantDTO;
import com.appsmith.server.grants.AppGrantService;
import com.appsmith.server.grants.GrantLevel;
import com.appsmith.server.helpers.ce.bridge.Bridge;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.CacheableRepositoryHelper;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.roles.DefaultWorkspaceService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.roles.PublishApproval;
import com.appsmith.server.services.ApplicationPageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The home page catalog and app tags, through the real services, grants and permission checks. Every test tags its
 * apps with its own run tag and filters by it, so apps other tests left in the database never show up.
 */
@SpringBootTest
@DirtiesContext
class ApplicationCatalogTest {

    @Autowired
    ReactiveWebApplicationContext context;

    @Autowired
    ApplicationCatalogService catalogService;

    @Autowired
    AppGrantService appGrantService;

    @Autowired
    ApplicationApprovalService approvalService;

    @Autowired
    ApprovalFingerprint fingerprint;

    @Autowired
    ApplicationPageService applicationPageService;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    DefaultWorkspaceService defaultWorkspaceService;

    @Autowired
    InstanceRoleService instanceRoleService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CacheableRepositoryHelper cacheableRepositoryHelper;

    WebTestClient webTestClient;
    String workspaceId;
    String run;
    User admin;
    User dev;
    User approver;
    User viewer;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();
        workspaceId = defaultWorkspaceService.getIds().block().workspaceId();
        run = "run-" + UUID.randomUUID().toString().substring(0, 8);
        admin = createUserWithRole(InstanceRole.ADMIN);
        dev = createUserWithRole(InstanceRole.DEV);
        approver = createUserWithRole(InstanceRole.APPROVER);
        viewer = createUserWithRole(InstanceRole.USER);
    }

    @Test
    void should_listOnlyGrantedApps_when_devHoldsGrants() {
        // Given
        Application viewed = createApp("viewed");
        Application edited = createApp("edited");
        createApp("hidden");
        grant(viewed, dev, GrantLevel.VIEW);
        grant(edited, dev, GrantLevel.EDIT);

        // When
        ApplicationCatalogDTO catalog = catalogOf(dev, query(null, List.of(run), null, null, null, null, null));

        // Then
        assertThat(catalog.total()).isEqualTo(2);
        assertThat(catalog.items())
                .extracting(ApplicationCatalogDTO.Item::id, ApplicationCatalogDTO.Item::canEdit)
                .containsExactlyInAnyOrder(tuple(viewed.getId(), false), tuple(edited.getId(), true));
    }

    @Test
    void should_listEveryAppAsEditable_when_callerIsAdmin() {
        // Given
        createApp("one");
        createApp("two");

        // When
        ApplicationCatalogDTO catalog = catalogOf(admin, query(null, List.of(run), null, null, null, null, null));

        // Then
        assertThat(catalog.total()).isEqualTo(2);
        assertThat(catalog.items())
                .allSatisfy(item -> assertThat(item.canEdit()).isTrue());
    }

    @Test
    void should_listAppsReadOnly_when_callerIsApprover() {
        // Given
        createApp("one");

        // When
        ApplicationCatalogDTO catalog = catalogOf(approver, query(null, List.of(run), null, null, null, null, null));

        // Then
        assertThat(catalog.items()).singleElement().satisfies(item -> assertThat(item.canEdit())
                .isFalse());
    }

    @Test
    void should_listOnlyLiveApps_when_callerIsUser() {
        // Given
        Application live = createApp("live");
        Application draft = createApp("draft");
        grant(live, viewer, GrantLevel.EDIT);
        grant(draft, viewer, GrantLevel.VIEW);
        makeLive(live);

        // When
        ApplicationCatalogDTO catalog = catalogOf(viewer, query(null, List.of(run), null, null, null, null, null));

        // Then
        assertThat(catalog.total()).isEqualTo(1);
        assertThat(catalog.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(live.getId());
            assertThat(item.lastDeployedAt()).isNotNull();
            assertThat(item.canEdit()).isFalse();
            assertThat(item.approvalStatus()).isNull();
            assertThat(item.defaultPageId()).isNotBlank();
            assertThat(item.defaultPageSlug()).isNotBlank();
            assertThat(item.slug()).isEqualTo(live.getSlug());
        });
    }

    @Test
    void should_matchNameDescriptionAndTags_when_searching() {
        // Given
        String needle = "nd" + UUID.randomUUID().toString().substring(0, 6);
        Application byName = createApp("Report " + needle.toUpperCase());
        Application byDescription = createApp("plain-a");
        setDescription(byDescription, "Shows the " + needle + " numbers");
        Application byTag = createApp("plain-b", needle + "-team");
        createApp("plain-c");

        // When
        ApplicationCatalogDTO catalog = catalogOf(admin, query(needle, List.of(run), null, null, null, null, null));

        // Then
        assertThat(catalog.items())
                .extracting(ApplicationCatalogDTO.Item::id)
                .containsExactlyInAnyOrder(byName.getId(), byDescription.getId(), byTag.getId());
    }

    @Test
    void should_matchLiterally_when_searchHasRegexCharacters() {
        // Given
        createApp("axb");
        Application literal = createApp("a.b");

        // When
        ApplicationCatalogDTO catalog = catalogOf(admin, query("a.b", List.of(run), null, null, null, null, null));

        // Then
        assertThat(catalog.items()).extracting(ApplicationCatalogDTO.Item::id).containsExactly(literal.getId());
    }

    @ParameterizedTest
    @CsvSource({"any,3", "all,1", ",3"})
    void should_filterByTags_when_tagModeIsGiven(String tagMode, int expected) {
        // Given
        String x = run + "-x";
        String y = run + "-y";
        createApp("only-x", x);
        createApp("only-y", y);
        Application both = createApp("both", x, y);

        // When
        ApplicationCatalogDTO catalog =
                catalogOf(admin, query(null, List.of(x + "," + y), tagMode, null, null, null, null));

        // Then
        assertThat(catalog.total()).isEqualTo(expected);
        if (expected == 1) {
            assertThat(catalog.items())
                    .extracting(ApplicationCatalogDTO.Item::id)
                    .containsExactly(both.getId());
        }
    }

    @Test
    void should_sortByNameAndPage_when_pageSizeIsSmall() {
        // Given
        createApp("c");
        createApp("a");
        createApp("b");

        // When
        ApplicationCatalogDTO first = catalogOf(admin, query(null, List.of(run), null, "name", "asc", 0, 2));
        ApplicationCatalogDTO second = catalogOf(admin, query(null, List.of(run), null, "name", "asc", 1, 2));
        ApplicationCatalogDTO descending = catalogOf(admin, query(null, List.of(run), null, "name", "desc", 0, 2));

        // Then
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(ApplicationCatalogDTO.Item::name).containsExactly(run + "-a", run + "-b");
        assertThat(second.items()).extracting(ApplicationCatalogDTO.Item::name).containsExactly(run + "-c");
        assertThat(descending.items())
                .extracting(ApplicationCatalogDTO.Item::name)
                .containsExactly(run + "-c", run + "-b");
    }

    @Test
    void should_sortByCreatedAtNewestFirst_when_noOrderIsGiven() {
        // Given
        Application older = createApp("older");
        Application newer = createApp("newer");

        // When
        ApplicationCatalogDTO catalog =
                catalogOf(admin, query(null, List.of(run), null, "createdAt", null, null, null));

        // Then
        assertThat(catalog.items())
                .extracting(ApplicationCatalogDTO.Item::id)
                .containsExactly(newer.getId(), older.getId());
    }

    @ParameterizedTest
    @CsvSource({"size,101", "size,0", "page,-1", "sort,slug", "order,up", "tagMode,some"})
    void should_refuseQuery_when_parameterIsInvalid(String parameter, String value) {
        // Given
        ApplicationCatalogService.CatalogQuery query = query(
                null,
                null,
                parameter.equals("tagMode") ? value : null,
                parameter.equals("sort") ? value : null,
                parameter.equals("order") ? value : null,
                parameter.equals("page") ? Integer.valueOf(value) : null,
                parameter.equals("size") ? Integer.valueOf(value) : null);

        // When
        Optional<AppsmithError> error = errorOf(catalogService.getCatalog(query), admin);

        // Then
        assertThat(error).contains(AppsmithError.INVALID_PARAMETER);
    }

    @Test
    void should_answerCatalogOverHttp_when_userIsSignedIn() {
        // Given
        Application app = createApp("http", run + "-chip");

        // When / Then
        clientFor(admin)
                .get()
                .uri(uri -> uri.path("/api/v1/applications/catalog")
                        .queryParam("tags", run + "-chip")
                        .queryParam("size", 5)
                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.total")
                .isEqualTo(1)
                .jsonPath("$.data.items[0].id")
                .isEqualTo(app.getId())
                .jsonPath("$.data.items[0].tags[0]")
                .isEqualTo(run)
                .jsonPath("$.data.size")
                .isEqualTo(5);
    }

    @Test
    void should_listDistinctTagsOfReadableApps_when_devReadsTags() {
        // Given
        Application readable = createApp("readable", run + "-shared", run + "-mine");
        createApp("other", run + "-shared", run + "-secret");
        grant(readable, dev, GrantLevel.VIEW);

        // When
        List<String> tags = runAs(catalogService.getTags(), dev).block();

        // Then
        assertThat(tags).contains(run, run + "-shared", run + "-mine").doesNotContain(run + "-secret");
        assertThat(tags).isSorted().doesNotHaveDuplicates();
        assertThat(runAs(catalogService.getTags(), admin).block()).contains(run + "-secret");
    }

    @Test
    void should_storeNormalisedTags_when_devWithEditSetsThem() {
        // Given
        Application app = createApp("tagged");
        grant(app, dev, GrantLevel.EDIT);

        // When / Then
        clientFor(dev)
                .put()
                .uri("/api/v1/app-tags/applications/{id}", app.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tags", List.of(" Sales ", "sales", "HR   Team", "")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.tags[0]")
                .isEqualTo("sales")
                .jsonPath("$.data.tags[1]")
                .isEqualTo("hr team");
        assertThat(applicationRepository.findById(app.getId()).block().getTags())
                .containsExactly("sales", "hr team");
    }

    @Test
    void should_refuseTags_when_overLimits() {
        // Given
        Application app = createApp("limits");
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "tag" + i).toList();
        String tooLong = "x".repeat(ApplicationTags.MAX_LENGTH + 1);

        // When
        Optional<AppsmithError> tooMany = errorOf(catalogService.setTags(app.getId(), eleven), admin);
        Optional<AppsmithError> overLength = errorOf(catalogService.setTags(app.getId(), List.of(tooLong)), admin);

        // Then
        assertThat(tooMany).contains(AppsmithError.INVALID_PARAMETER);
        assertThat(overLength).contains(AppsmithError.INVALID_PARAMETER);
        assertThat(applicationRepository.findById(app.getId()).block().getTags())
                .containsExactly(run);
    }

    @Test
    void should_refuseTags_when_devHoldsOnlyView() {
        // Given
        Application app = createApp("view-only");
        grant(app, dev, GrantLevel.VIEW);

        // When
        Optional<AppsmithError> error = errorOf(catalogService.setTags(app.getId(), List.of("mine")), dev);

        // Then
        assertThat(error).contains(AppsmithError.ACL_NO_RESOURCE_FOUND);
        assertThat(applicationRepository.findById(app.getId()).block().getTags())
                .containsExactly(run);
    }

    @Test
    void should_refuseTags_when_userHoldsEditGrant() {
        // Given
        Application app = createApp("user-edit");
        grant(app, viewer, GrantLevel.EDIT);
        makeLive(app);

        // When
        Optional<AppsmithError> error = errorOf(catalogService.setTags(app.getId(), List.of("mine")), viewer);

        // Then
        assertThat(error).contains(AppsmithError.ACTION_IS_NOT_AUTHORIZED);
    }

    @Test
    void should_refuseTagsOverHttp_when_callerIsApprover() {
        // Given
        Application app = createApp("approver");

        // When / Then
        clientFor(approver)
                .put()
                .uri("/api/v1/app-tags/applications/{id}", app.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tags", List.of("mine")))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(applicationRepository.findById(app.getId()).block().getTags())
                .containsExactly(run);
    }

    @Test
    void should_keepRequestSubmitted_when_tagsChange() {
        // Given
        Application app = createApp("submitted");
        grant(app, dev, GrantLevel.EDIT);
        runAs(approvalService.submit(app.getId()), dev).block();
        String editModeBefore = fingerprint.ofEditMode(app.getId()).block();

        // When
        runAs(catalogService.setTags(app.getId(), List.of(run, "finance")), dev).block();

        // Then
        assertThat(fingerprint.ofEditMode(app.getId()).block()).isEqualTo(editModeBefore);
        assertThat(runAs(approvalService.getStatus(app.getId()), dev).block().status())
                .isEqualTo(ApprovalStatus.SUBMITTED);
        assertThat(catalogOf(dev, query(null, List.of(run), null, null, null, null, null))
                        .items())
                .extracting(ApplicationCatalogDTO.Item::id, ApplicationCatalogDTO.Item::approvalStatus)
                .containsExactly(tuple(app.getId(), ApprovalStatus.SUBMITTED));
    }

    @Test
    void should_keepAppApprovedAndLive_when_tagsChange() {
        // Given
        Application app = createApp("approved");
        grant(app, dev, GrantLevel.EDIT);
        runAs(approvalService.submit(app.getId()), dev).block();
        runAs(approvalService.approve(app.getId()), approver).block();
        String viewModeBefore = fingerprint.ofViewMode(app.getId()).block();

        // When
        runAs(catalogService.setTags(app.getId(), List.of("finance", "q3")), dev)
                .block();

        // Then
        assertThat(fingerprint.ofViewMode(app.getId()).block()).isEqualTo(viewModeBefore);
        assertThat(runAs(approvalService.getStatus(app.getId()), dev).block().status())
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(applicationRepository.findById(app.getId()).block().getLastDeployedAt())
                .isNotNull();
    }

    /** An app named {@code <run>-<suffix>}, created by the admin, tagged with the run tag and {@code extraTags}. */
    private Application createApp(String suffix, String... extraTags) {
        Application unsaved = new Application();
        unsaved.setName(run + "-" + suffix);
        Application created = runAs(applicationPageService.createApplication(unsaved, workspaceId), admin)
                .block();
        List<String> tags = new ArrayList<>(List.of(run));
        tags.addAll(List.of(extraTags));
        runAs(catalogService.setTags(created.getId(), tags), admin).block();
        return applicationRepository.findById(created.getId()).block();
    }

    private void setDescription(Application application, String description) {
        applicationRepository
                .queryBuilder()
                .byId(application.getId())
                .updateFirst(Bridge.update().set(Application.Fields.description, description))
                .block();
    }

    private void grant(Application application, User user, GrantLevel level) {
        runAs(
                        appGrantService.setGrant(
                                application.getId(),
                                new AppGrantDTO.Request(
                                        new AppGrantDTO.Grantee(AppGrantDTO.Type.USER, user.getId()), level)),
                        admin)
                .block();
    }

    private void makeLive(Application application) {
        runAs(PublishApproval.asApprovedRelease(applicationPageService.publish(application.getId(), true)), admin)
                .block();
    }

    private ApplicationCatalogDTO catalogOf(User user, ApplicationCatalogService.CatalogQuery query) {
        return runAs(catalogService.getCatalog(query), user).block();
    }

    private static ApplicationCatalogService.CatalogQuery query(
            String search, List<String> tags, String tagMode, String sort, String order, Integer page, Integer size) {
        return new ApplicationCatalogService.CatalogQuery(search, tags, tagMode, sort, order, page, size);
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
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@catalog.example.com");
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
}

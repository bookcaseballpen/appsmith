package com.appsmith.server.editorlock;

import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.McpConfig;
import com.appsmith.server.domains.Organization;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.McpTokenResponseDTO;
import com.appsmith.server.enums.McpKeyStatus;
import com.appsmith.server.newpages.base.NewPageService;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.McpTokenService;
import com.appsmith.server.services.OrganizationService;
import com.appsmith.server.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Drives the read-only editor guard through the full security chain: a session user (the editor) is refused when it
 * renames a page, the same call with a real {@code mcp_} bearer and the internal marker goes through, and running a
 * query and importing an app are not refused by the guard. The test properties turn the guard off for the upstream
 * suites; this class turns it back on.
 */
@SpringBootTest(
        properties = {
            "appsmith.mcp.internal-secret=" + EditorReadOnlyHttpTest.MCP_INTERNAL_SECRET,
            "appsmith.editor.read-only=true"
        })
class EditorReadOnlyHttpTest {

    static final String MCP_INTERNAL_SECRET = "editor-read-only-http-test-secret";

    private static final String TOKEN_NAME = "EditorReadOnlyHttpTest";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private ApplicationPageService applicationPageService;

    @Autowired
    private NewPageService newPageService;

    @Autowired
    private McpTokenService mcpTokenService;

    private WebTestClient webTestClient;
    private User apiUser;
    private Authentication apiUserAuthentication;
    private boolean mcpConfigReplaced;
    private McpConfig originalMcpConfig;
    private McpTokenResponseDTO mcpToken;
    private String workspaceId;
    private String applicationId;
    private String pageId;

    @BeforeEach
    void setup(ReactiveWebApplicationContext context) {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();

        apiUser = userRepository.findByEmail("api_user").block();
        apiUserAuthentication =
                UsernamePasswordAuthenticationToken.authenticated(apiUser, null, apiUser.getAuthorities());

        // Token minting and token authentication both refuse unless the organization has MCP enabled.
        Organization organization =
                asApiUser(organizationService.getCurrentUserOrganization()).block();
        originalMcpConfig = organization.getOrganizationConfiguration().getMcpConfig();
        McpConfig enabledMcpConfig = new McpConfig();
        enabledMcpConfig.setEnabled(true);
        organization.getOrganizationConfiguration().setMcpConfig(enabledMcpConfig);
        organizationService.save(organization).block();
        mcpConfigReplaced = true;

        // A run that was killed before tearDown leaves its key active, and api_user may hold only a few active keys.
        asApiUser(mcpTokenService
                        .list(apiUser)
                        .filter(key -> TOKEN_NAME.equals(key.name()) && McpKeyStatus.ACTIVE.equals(key.status()))
                        .flatMap(key -> mcpTokenService.revoke(apiUser, key.id()))
                        .then())
                .block();

        Workspace workspace = new Workspace();
        workspace.setName("EditorReadOnlyHttpTest-" + UUID.randomUUID());
        workspaceId = asApiUser(workspaceService.create(workspace)).block().getId();

        Application application = new Application();
        application.setName("EditorReadOnlyHttpTest");
        Application created = asApiUser(applicationPageService.createApplication(application, workspaceId))
                .block();
        applicationId = created.getId();
        pageId = created.getPages().get(0).getId();

        mcpToken = asApiUser(mcpTokenService.create(apiUser, TOKEN_NAME)).block();
        assertThat(mcpToken.token()).startsWith("mcp_");
    }

    @AfterEach
    void tearDown() {
        try {
            if (mcpToken != null) {
                asApiUser(mcpTokenService.revoke(apiUser, mcpToken.id())).block();
            }
            if (applicationId != null) {
                asApiUser(applicationPageService.deleteApplication(applicationId))
                        .block();
            }
            if (workspaceId != null) {
                asApiUser(workspaceService.archiveById(workspaceId)).block();
            }
        } finally {
            if (mcpConfigReplaced) {
                Organization organization = asApiUser(organizationService.getCurrentUserOrganization())
                        .block();
                organization.getOrganizationConfiguration().setMcpConfig(originalMcpConfig);
                organizationService.save(organization).block();
            }
        }
    }

    @Test
    void should_return403WithMessage_when_sessionUserRenamesPage() {
        // Given
        Map<String, String> body = Map.of("name", "RenamedInEditor");

        // When
        WebTestClient.ResponseSpec response = webTestClient
                .mutateWith(mockAuthentication(apiUserAuthentication))
                .put()
                .uri("/api/v1/pages/{pageId}", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();

        // Then
        response.expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.responseMeta.error.message")
                .isEqualTo(EditorReadOnlyWebFilter.MESSAGE);
        assertThat(pageName()).isEqualTo("Page1");
    }

    @Test
    void should_renamePage_when_mcpPrincipalRenamesPage() {
        // Given
        Map<String, String> body = Map.of("name", "RenamedByMcp");

        // When
        WebTestClient.ResponseSpec response = webTestClient
                .put()
                .uri("/api/v1/pages/{pageId}", pageId)
                .headers(headers -> {
                    headers.setBearerAuth(mcpToken.token());
                    headers.set(FieldName.INTERNAL_MARKER, MCP_INTERNAL_SECRET);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();

        // Then
        response.expectStatus().isOk();
        assertThat(pageName()).isEqualTo("RenamedByMcp");
    }

    @Test
    void should_notRefuse_when_sessionUserRunsQuery() {
        // Given
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("executeActionDTO", "{\"actionId\":\"missing-action\",\"viewMode\":false}");

        // When
        EntityExchangeResult<String> result = webTestClient
                .mutateWith(mockAuthentication(apiUserAuthentication))
                .post()
                .uri("/api/v1/actions/execute")
                .header("X-Requested-By", "Appsmith")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectBody(String.class)
                .returnResult();

        // Then: the guard let it through; the service answers for the unknown action.
        assertNotRefusedByGuard(result);
    }

    @Test
    void should_notRefuse_when_sessionUserImportsApp() {
        // Given
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", "{}".getBytes()).filename("app.json").contentType(MediaType.APPLICATION_JSON);

        // When
        EntityExchangeResult<String> result = webTestClient
                .mutateWith(mockAuthentication(apiUserAuthentication))
                .post()
                .uri("/api/v1/applications/import/{workspaceId}", workspaceId)
                .header("X-Requested-By", "Appsmith")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectBody(String.class)
                .returnResult();

        // Then: the guard let it through; the import service rejects the empty file on its own.
        assertNotRefusedByGuard(result);
    }

    @Test
    void should_sendReadOnlyFlag_when_clientFetchesFeatureFlags() {
        // When
        WebTestClient.ResponseSpec response = webTestClient
                .mutateWith(mockAuthentication(apiUserAuthentication))
                .get()
                .uri("/api/v1/users/features")
                .exchange();

        // Then
        response.expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data." + EditorReadOnlySetting.FEATURE_FLAG)
                .isEqualTo(true);
    }

    private static void assertNotRefusedByGuard(EntityExchangeResult<String> result) {
        assertThat(result.getStatus().value()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(String.valueOf(result.getResponseBody())).doesNotContain(EditorReadOnlyWebFilter.MESSAGE);
    }

    private String pageName() {
        return asApiUser(newPageService.findById(pageId, null))
                .block()
                .getUnpublishedPage()
                .getName();
    }

    private <T> Mono<T> asApiUser(Mono<T> mono) {
        return mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(apiUserAuthentication));
    }
}

package com.appsmith.server.hidden;

import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.exceptions.AppsmithErrorCode;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Drives the hidden endpoints through the full security chain with the fork's production default for the built-in Git
 * switch ({@code APPSMITH_GIT_ENABLED} off; the other integration suites run with it on).
 */
@SpringBootTest(properties = "appsmith.git.enabled=false")
class HiddenEndpointsHttpTest {

    @Autowired
    private ReactiveWebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        User apiUser = userRepository.findByEmail("api_user").block();
        client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build()
                .mutateWith(mockAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(apiUser, null, apiUser.getAuthorities())))
                .mutateWith(csrf());
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/git/applications/app1/metadata, Built-in Git",
        "POST, /api/v1/git/applications/app1/connect, Built-in Git",
        "POST, /api/v1/git/artifacts/import?workspaceId=ws1, Built-in Git",
        "GET, /api/v1/git/artifacts/import/keys?keyType=ECDSA, Built-in Git",
        "GET, /api/v1/applications/ssh-keypair/app1, Built-in Git",
        "GET, /api/v1/app-templates, The template gallery",
        "GET, /api/v1/app-templates/filters, The template gallery",
        "POST, /api/v1/app-templates/t1/import/ws1, The template gallery",
        "DELETE, /api/v1/workspaces/ws1, Creating or deleting workspaces",
    })
    void should_answerFeatureDisabled_when_hiddenEndpointIsCalled(String method, String uri, String featureName) {
        // When
        WebTestClient.ResponseSpec response = client.method(HttpMethod.valueOf(method))
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange();

        // Then
        response.expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.responseMeta.error.code")
                .isEqualTo(AppsmithErrorCode.FEATURE_DISABLED.getCode())
                .jsonPath("$.responseMeta.error.message")
                .isEqualTo(featureName + " is disabled on this instance.");
    }

    @Test
    void should_createNoWorkspace_when_createIsRequested() {
        // Given
        String name = "hidden-endpoints-" + UUID.randomUUID();
        Workspace request = new Workspace();
        request.setName(name);

        // When
        WebTestClient.ResponseSpec response = client.post()
                .uri("/api/v1/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange();

        // Then
        response.expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.responseMeta.error.message")
                .isEqualTo("Creating or deleting workspaces is disabled on this instance.");
        assertThat(workspaceRepository
                        .retrieveAll()
                        .filter(workspace -> name.equals(workspace.getName()))
                        .count()
                        .block())
                .isZero();
    }

    @Test
    void should_letTheRequestThrough_when_endpointIsNotHidden() {
        // When
        WebTestClient.ResponseSpec response =
                client.get().uri("/api/v1/workspaces/home").exchange();

        // Then
        response.expectStatus().isOk();
    }
}

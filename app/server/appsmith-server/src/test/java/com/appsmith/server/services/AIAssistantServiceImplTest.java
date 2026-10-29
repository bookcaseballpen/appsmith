package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.solutions.ActionPermission;
import com.appsmith.server.solutions.DatasourceStructureSolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIAssistantServiceImplTest {

    @Mock
    OrganizationService organizationService;

    @Mock
    AIReferenceService aiReferenceService;

    @Mock
    NewActionService newActionService;

    @Mock
    DatasourceStructureSolution datasourceStructureSolution;

    @Mock
    PluginService pluginService;

    @Mock
    ActionPermission actionPermission;

    @Mock
    InstanceRoleService instanceRoleService;

    private AIAssistantServiceImpl service(boolean builtInAiEnabled) {
        return new AIAssistantServiceImpl(
                organizationService,
                aiReferenceService,
                newActionService,
                datasourceStructureSolution,
                pluginService,
                actionPermission,
                instanceRoleService,
                new BuiltInAiConfig(builtInAiEnabled));
    }

    @Test
    void should_refuseEveryRole_when_builtInAiIsSwitchedOff() {
        // Given
        AIAssistantServiceImpl service = service(false);

        // When
        Mono<String> withoutHistory = service.getAIResponse("OPENAI", "hello", null);
        Mono<String> withHistory = service.getAIResponse("OPENAI", "hello", null, List.of());

        // Then
        for (Mono<String> response : List.of(withoutHistory, withHistory)) {
            StepVerifier.create(response)
                    .expectErrorSatisfies(error -> assertThat(((AppsmithException) error).getError())
                            .isEqualTo(AppsmithError.FEATURE_DISABLED))
                    .verify();
        }
        verifyNoInteractions(instanceRoleService, organizationService);
    }

    @Test
    void should_keepTheRoleGate_when_builtInAiIsSwitchedOn() {
        // Given
        when(instanceRoleService.requireCurrentUserRole("Use the AI assistant", InstanceRole.ADMIN, InstanceRole.DEV))
                .thenReturn(Mono.error(
                        new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, "Use the AI assistant")));

        // When
        Mono<String> response = service(true).getAIResponse("OPENAI", "hello", null);

        // Then
        StepVerifier.create(response)
                .expectErrorSatisfies(error -> assertThat(((AppsmithException) error).getError())
                        .isEqualTo(AppsmithError.ACTION_IS_NOT_AUTHORIZED))
                .verify();
        verifyNoInteractions(organizationService);
    }
}

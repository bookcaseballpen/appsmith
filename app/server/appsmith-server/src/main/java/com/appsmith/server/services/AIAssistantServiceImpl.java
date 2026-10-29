package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.dtos.AIEditorContextDTO;
import com.appsmith.server.dtos.AIMessageDTO;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.roles.InstanceRole;
import com.appsmith.server.roles.InstanceRoleService;
import com.appsmith.server.services.ce.AIAssistantServiceCEImpl;
import com.appsmith.server.solutions.ActionPermission;
import com.appsmith.server.solutions.DatasourceStructureSolution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
public class AIAssistantServiceImpl extends AIAssistantServiceCEImpl implements AIAssistantService {

    private final InstanceRoleService instanceRoleService;
    private final BuiltInAiConfig builtInAiConfig;

    public AIAssistantServiceImpl(
            OrganizationService organizationService,
            AIReferenceService aiReferenceService,
            NewActionService newActionService,
            DatasourceStructureSolution datasourceStructureSolution,
            PluginService pluginService,
            ActionPermission actionPermission,
            InstanceRoleService instanceRoleService,
            BuiltInAiConfig builtInAiConfig) {
        super(
                organizationService,
                aiReferenceService,
                newActionService,
                datasourceStructureSolution,
                pluginService,
                actionPermission);
        this.instanceRoleService = instanceRoleService;
        this.builtInAiConfig = builtInAiConfig;
    }

    // Only when the built-in AI is switched on (APPSMITH_AI_ENABLED), and only the roles that edit apps may ask the
    // AI assistant.

    @Override
    public Mono<String> getAIResponse(String provider, String prompt, AIEditorContextDTO context) {
        return requireAiAccess().then(Mono.defer(() -> super.getAIResponse(provider, prompt, context)));
    }

    @Override
    public Mono<String> getAIResponse(
            String provider, String prompt, AIEditorContextDTO context, List<AIMessageDTO> conversationHistory) {
        return requireAiAccess()
                .then(Mono.defer(() -> super.getAIResponse(provider, prompt, context, conversationHistory)));
    }

    private Mono<Void> requireAiAccess() {
        return builtInAiConfig
                .requireEnabled()
                .then(Mono.defer(() -> instanceRoleService.requireCurrentUserRole(
                        "Use the AI assistant", InstanceRole.ADMIN, InstanceRole.DEV)));
    }
}

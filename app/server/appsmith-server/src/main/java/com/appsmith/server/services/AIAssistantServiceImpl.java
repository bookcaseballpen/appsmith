package com.appsmith.server.services;

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

    public AIAssistantServiceImpl(
            OrganizationService organizationService,
            AIReferenceService aiReferenceService,
            NewActionService newActionService,
            DatasourceStructureSolution datasourceStructureSolution,
            PluginService pluginService,
            ActionPermission actionPermission,
            InstanceRoleService instanceRoleService) {
        super(
                organizationService,
                aiReferenceService,
                newActionService,
                datasourceStructureSolution,
                pluginService,
                actionPermission);
        this.instanceRoleService = instanceRoleService;
    }

    // Only the roles that edit apps may ask the AI assistant.

    @Override
    public Mono<String> getAIResponse(String provider, String prompt, AIEditorContextDTO context) {
        return requireEditRole().then(Mono.defer(() -> super.getAIResponse(provider, prompt, context)));
    }

    @Override
    public Mono<String> getAIResponse(
            String provider, String prompt, AIEditorContextDTO context, List<AIMessageDTO> conversationHistory) {
        return requireEditRole()
                .then(Mono.defer(() -> super.getAIResponse(provider, prompt, context, conversationHistory)));
    }

    private Mono<Void> requireEditRole() {
        return instanceRoleService.requireCurrentUserRole("Use the AI assistant", InstanceRole.ADMIN, InstanceRole.DEV);
    }
}

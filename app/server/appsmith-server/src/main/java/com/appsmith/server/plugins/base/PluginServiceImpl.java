package com.appsmith.server.plugins.base;

import com.appsmith.server.configurations.CloudServicesConfig;
import com.appsmith.server.plugins.solutions.PluginTransformationSolution;
import com.appsmith.server.repositories.PluginRepository;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.services.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PluginServiceImpl extends PluginServiceCEImpl implements PluginService {

    private final CloudServicesConfig cloudServicesConfig;

    public PluginServiceImpl(
            Validator validator,
            PluginRepository repository,
            AnalyticsService analyticsService,
            WorkspaceService workspaceService,
            PluginManager pluginManager,
            ObjectMapper objectMapper,
            CloudServicesConfig cloudServicesConfig,
            ConfigService configService,
            PluginTransformationSolution pluginTransformationSolution) {

        super(
                validator,
                repository,
                analyticsService,
                workspaceService,
                pluginManager,
                objectMapper,
                cloudServicesConfig,
                configService,
                pluginTransformationSolution);
        this.cloudServicesConfig = cloudServicesConfig;
    }

    /**
     * Fork: the "upcoming integrations" list comes from cloud services, so it is empty when they are disabled.
     */
    @Override
    public Mono<List<Map<String, String>>> getUpcomingIntegrations() {
        if (!cloudServicesConfig.isEnabled()) {
            return Mono.just(List.of());
        }
        return super.getUpcomingIntegrations();
    }
}

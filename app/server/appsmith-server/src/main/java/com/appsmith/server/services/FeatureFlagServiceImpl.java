package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.configurations.BuiltInGitConfig;
import com.appsmith.server.helpers.FeatureFlagMigrationHelper;
import com.appsmith.server.services.ce.FeatureFlagServiceCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Component
public class FeatureFlagServiceImpl extends FeatureFlagServiceCEImpl implements FeatureFlagService {

    private final BuiltInAiConfig builtInAiConfig;

    private final BuiltInGitConfig builtInGitConfig;

    public FeatureFlagServiceImpl(
            SessionUserService sessionUserService,
            OrganizationService organizationService,
            UserIdentifierService userIdentifierService,
            CacheableFeatureFlagHelper cacheableFeatureFlagHelper,
            FeatureFlagMigrationHelper featureFlagMigrationHelper,
            BuiltInAiConfig builtInAiConfig,
            BuiltInGitConfig builtInGitConfig) {
        super(
                sessionUserService,
                organizationService,
                userIdentifierService,
                cacheableFeatureFlagHelper,
                featureFlagMigrationHelper);
        this.builtInAiConfig = builtInAiConfig;
        this.builtInGitConfig = builtInGitConfig;
    }

    /**
     * Fork: adds the built-in AI switch ({@code APPSMITH_AI_ENABLED}) and the built-in Git switch
     * ({@code APPSMITH_GIT_ENABLED}) as flags. They are added here, after the cached user and organization flags, so a
     * changed switch applies on the next restart and a cached value never wins.
     */
    @Override
    public Mono<Map<String, Boolean>> getAllFeatureFlagsForUser() {
        return super.getAllFeatureFlagsForUser().map(flags -> {
            Map<String, Boolean> withSwitches = new HashMap<>(flags);
            withSwitches.put(BuiltInAiConfig.FEATURE_FLAG, builtInAiConfig.isEnabled());
            withSwitches.put(BuiltInGitConfig.FEATURE_FLAG, builtInGitConfig.isEnabled());
            return withSwitches;
        });
    }
}

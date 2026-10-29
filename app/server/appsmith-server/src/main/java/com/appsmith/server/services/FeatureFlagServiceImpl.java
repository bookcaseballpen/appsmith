package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.helpers.FeatureFlagMigrationHelper;
import com.appsmith.server.services.ce.FeatureFlagServiceCEImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Component
public class FeatureFlagServiceImpl extends FeatureFlagServiceCEImpl implements FeatureFlagService {

    private final BuiltInAiConfig builtInAiConfig;

    public FeatureFlagServiceImpl(
            SessionUserService sessionUserService,
            OrganizationService organizationService,
            UserIdentifierService userIdentifierService,
            CacheableFeatureFlagHelper cacheableFeatureFlagHelper,
            FeatureFlagMigrationHelper featureFlagMigrationHelper,
            BuiltInAiConfig builtInAiConfig) {
        super(
                sessionUserService,
                organizationService,
                userIdentifierService,
                cacheableFeatureFlagHelper,
                featureFlagMigrationHelper);
        this.builtInAiConfig = builtInAiConfig;
    }

    /**
     * Fork: adds the built-in AI switch ({@code APPSMITH_AI_ENABLED}) as a flag. It is added here, after the cached
     * user and organization flags, so a changed switch applies on the next restart and a cached value never wins.
     */
    @Override
    public Mono<Map<String, Boolean>> getAllFeatureFlagsForUser() {
        return super.getAllFeatureFlagsForUser().map(flags -> {
            Map<String, Boolean> withSwitches = new HashMap<>(flags);
            withSwitches.put(BuiltInAiConfig.FEATURE_FLAG, builtInAiConfig.isEnabled());
            return withSwitches;
        });
    }
}

package com.appsmith.server.services;

import com.appsmith.server.configurations.BuiltInAiConfig;
import com.appsmith.server.configurations.BuiltInGitConfig;
import com.appsmith.server.editorlock.EditorReadOnlySetting;
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

    private final EditorReadOnlySetting editorReadOnlySetting;

    public FeatureFlagServiceImpl(
            SessionUserService sessionUserService,
            OrganizationService organizationService,
            UserIdentifierService userIdentifierService,
            CacheableFeatureFlagHelper cacheableFeatureFlagHelper,
            FeatureFlagMigrationHelper featureFlagMigrationHelper,
            BuiltInAiConfig builtInAiConfig,
            BuiltInGitConfig builtInGitConfig,
            EditorReadOnlySetting editorReadOnlySetting) {
        super(
                sessionUserService,
                organizationService,
                userIdentifierService,
                cacheableFeatureFlagHelper,
                featureFlagMigrationHelper);
        this.builtInAiConfig = builtInAiConfig;
        this.builtInGitConfig = builtInGitConfig;
        this.editorReadOnlySetting = editorReadOnlySetting;
    }

    /**
     * Fork: adds the built-in AI switch ({@code APPSMITH_AI_ENABLED}), the built-in Git switch
     * ({@code APPSMITH_GIT_ENABLED}) and the read-only editor switch ({@link EditorReadOnlySetting#FEATURE_FLAG}, so the
     * client hides the editing UI when the server refuses editor changes) as flags. They are added here, after the
     * cached user and organization flags, so a changed switch applies on the next restart and a cached value never wins.
     */
    @Override
    public Mono<Map<String, Boolean>> getAllFeatureFlagsForUser() {
        return super.getAllFeatureFlagsForUser().map(flags -> {
            Map<String, Boolean> withSwitches = new HashMap<>(flags);
            withSwitches.put(BuiltInAiConfig.FEATURE_FLAG, builtInAiConfig.isEnabled());
            withSwitches.put(BuiltInGitConfig.FEATURE_FLAG, builtInGitConfig.isEnabled());
            withSwitches.put(EditorReadOnlySetting.FEATURE_FLAG, editorReadOnlySetting.isEnabled());
            return withSwitches;
        });
    }
}

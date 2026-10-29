package com.appsmith.server.configurations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fork switch for Appsmith's built-in Git (connect, branches, commit, pull, deploy keys, import from Git),
 * {@code APPSMITH_GIT_ENABLED}, off by default. Apps are versioned outside Appsmith instead (export and import, by the
 * AI, in a separate repository). While it is off the server refuses the Git endpoints
 * ({@link com.appsmith.server.hidden.HiddenEndpointsWebFilter}), and the client reads it as the {@link #FEATURE_FLAG}
 * feature flag to hide every Git entry point. The Git code itself stays.
 */
@Component
public class BuiltInGitConfig {

    /**
     * Feature flag the server adds to {@code /api/v1/users/features}, see {@code FeatureFlagServiceImpl}.
     */
    public static final String FEATURE_FLAG = "fork_builtin_git_enabled";

    public static final String FEATURE_NAME = "Built-in Git";

    private final boolean enabled;

    public BuiltInGitConfig(@Value("${appsmith.git.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}

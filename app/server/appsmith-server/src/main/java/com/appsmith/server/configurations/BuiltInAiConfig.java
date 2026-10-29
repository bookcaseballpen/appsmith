package com.appsmith.server.configurations;

import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Fork switch for the built-in AI features (AI assistant, Ask AI, AI settings), {@code APPSMITH_AI_ENABLED}, off by
 * default. The server refuses the AI endpoints while it is off, and the client reads it as the {@link #FEATURE_FLAG}
 * feature flag to hide every AI entry point. MCP does not depend on it.
 */
@Component
public class BuiltInAiConfig {

    /**
     * Feature flag the server adds to {@code /api/v1/users/features}, see {@code FeatureFlagServiceImpl}.
     */
    public static final String FEATURE_FLAG = "fork_builtin_ai_enabled";

    static final String FEATURE_NAME = "The built-in AI";

    private final boolean enabled;

    public BuiltInAiConfig(@Value("${appsmith.ai.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Completes when the built-in AI is on, errors with FEATURE_DISABLED otherwise.
     */
    public Mono<Void> requireEnabled() {
        return enabled ? Mono.empty() : Mono.error(new AppsmithException(AppsmithError.FEATURE_DISABLED, FEATURE_NAME));
    }
}

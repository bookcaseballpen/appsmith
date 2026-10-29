package com.appsmith.server.featureflags;

import java.util.HashMap;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Fork: local feature flag values, used instead of cs.appsmith.com when cloud services are disabled. The same map is
 * served to the client through {@code /api/v1/users/features}, where it is merged over the client defaults in
 * {@code ce/entities/FeatureFlag.ts}. Any flag not listed here resolves to {@code false}, as it does upstream when
 * cloud services are unreachable. Keep this list in sync with FORK.md.
 */
public final class ForkFeatureFlagDefaults {

    private static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
            // Git: keep the legacy Git UI and API contracts, allow protected branches and auto-commit.
            entry("license_git_branch_protection_enabled", true),
            entry("release_git_autocommit_feature_enabled", true),
            entry("release_git_autocommit_eligibility_enabled", true),
            entry("license_git_continuous_delivery_enabled", false),
            entry("release_git_modularisation_enabled", false),
            entry("release_git_api_contracts_enabled", false),
            entry("release_git_persist_branch_enabled", false),
            entry("release_git_reset_optimization_enabled", false),
            entry("ab_rts_git_reset_enabled", false),
            // Server behaviour
            entry("rollout_datasource_test_rate_limit_enabled", true),
            entry("release_alphabetical_ordering_enabled", true),
            // Client: no feature walkthroughs, no session recordings.
            entry("rollout_remove_feature_walkthrough_enabled", true),
            entry("kill_session_recordings_enabled", true),
            entry("config_user_session_recordings_enabled", false));

    private ForkFeatureFlagDefaults() {}

    /**
     * @return a mutable copy of the local flag values
     */
    public static Map<String, Boolean> get() {
        return new HashMap<>(DEFAULTS);
    }
}

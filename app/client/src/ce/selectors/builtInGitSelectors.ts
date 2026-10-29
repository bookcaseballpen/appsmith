import type { DefaultRootState } from "react-redux";
import { selectFeatureFlagCheck } from "ee/selectors/featureFlagsSelectors";
import { FEATURE_FLAG } from "ee/entities/FeatureFlag";

/**
 * Fork: the built-in Git switch (APPSMITH_GIT_ENABLED, off by default). The server sends it as the
 * `fork_builtin_git_enabled` feature flag and refuses the Git endpoints while it is off. Apps are versioned outside
 * Appsmith instead (export and import). While it is off, the client shows no Git UI (connect, branches, commit, pull,
 * deploy keys, Git settings and profile, import from Git) and makes no Git request when the editor loads.
 */
export function getIsBuiltInGitEnabled(state: DefaultRootState): boolean {
  return selectFeatureFlagCheck(state, FEATURE_FLAG.fork_builtin_git_enabled);
}

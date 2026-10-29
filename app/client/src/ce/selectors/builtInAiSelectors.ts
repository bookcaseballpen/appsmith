import type { DefaultRootState } from "react-redux";
import { selectFeatureFlagCheck } from "ee/selectors/featureFlagsSelectors";
import { FEATURE_FLAG } from "ee/entities/FeatureFlag";

/**
 * Fork: the built-in AI switch (APPSMITH_AI_ENABLED, off by default). The server sends it as the
 * `fork_builtin_ai_enabled` feature flag. While it is off, the client shows no AI assistant, Ask AI, AI settings or
 * AI datasources. MCP does not depend on it.
 */
export function getIsBuiltInAiEnabled(state: DefaultRootState): boolean {
  return selectFeatureFlagCheck(state, FEATURE_FLAG.fork_builtin_ai_enabled);
}

import type { DefaultRootState } from "react-redux";
import {
  getHasAIApiKey as getHasAIApiKeyCE,
  getIsAIEnabled as getIsAIEnabledCE,
  getIsAIPanelOpen as getIsAIPanelOpenCE,
} from "ce/selectors/aiAssistantSelectors";
import { getIsBuiltInAiEnabled } from "ee/selectors/builtInAiSelectors";

export * from "ce/selectors/aiAssistantSelectors";

// Fork: while the built-in AI is switched off (APPSMITH_AI_ENABLED), these report no API key, AI disabled and the
// panel closed, so the Ask AI button and slash command, the AI side panel and the custom widget AI default tab stay
// hidden whatever the AI settings say. These local exports take precedence over the `export *` above.

export function getHasAIApiKey(state: DefaultRootState): boolean {
  return getIsBuiltInAiEnabled(state) && getHasAIApiKeyCE(state);
}

export function getIsAIEnabled(state: DefaultRootState): boolean {
  return getIsBuiltInAiEnabled(state) && getIsAIEnabledCE(state);
}

export function getIsAIPanelOpen(state: DefaultRootState): boolean {
  return getIsBuiltInAiEnabled(state) && getIsAIPanelOpenCE(state);
}

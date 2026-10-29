import type { DefaultRootState } from "react-redux";
import {
  getHasAIApiKey,
  getIsAIEnabled,
  getIsAIPanelOpen,
} from "ee/selectors/aiAssistantSelectors";
import { getIsBuiltInAiEnabled } from "ee/selectors/builtInAiSelectors";

// AI configured, enabled and the panel open: only the built-in AI switch decides.
function stateWith(featureFlags: Record<string, boolean>) {
  return {
    aiAssistant: { hasApiKey: true, isEnabled: true, isPanelOpen: true },
    ui: { users: { featureFlag: { data: featureFlags, overriddenFlags: {} } } },
  } as unknown as DefaultRootState;
}

describe("built-in AI switch selectors", () => {
  it("report AI off and the panel closed while the switch is off", () => {
    const state = stateWith({ fork_builtin_ai_enabled: false });

    expect(getIsBuiltInAiEnabled(state)).toBe(false);
    expect(getHasAIApiKey(state)).toBe(false);
    expect(getIsAIEnabled(state)).toBe(false);
    expect(getIsAIPanelOpen(state)).toBe(false);
  });

  it("treat a server without the flag as switched off", () => {
    const state = stateWith({});

    expect(getIsBuiltInAiEnabled(state)).toBe(false);
    expect(getIsAIEnabled(state)).toBe(false);
  });

  it("pass the AI settings through while the switch is on", () => {
    const state = stateWith({ fork_builtin_ai_enabled: true });

    expect(getIsBuiltInAiEnabled(state)).toBe(true);
    expect(getHasAIApiKey(state)).toBe(true);
    expect(getIsAIEnabled(state)).toBe(true);
    expect(getIsAIPanelOpen(state)).toBe(true);
  });
});

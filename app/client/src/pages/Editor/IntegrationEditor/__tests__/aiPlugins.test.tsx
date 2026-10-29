import React from "react";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { DEFAULT_FEATURE_FLAG_VALUE } from "ee/entities/FeatureFlag";
import { fetchFeatureFlagsSuccess } from "actions/userActions";
import store from "store";
import { render } from "test/testUtils";
import { PluginPackageName } from "entities/Plugin";
import AIPlugins from "../AIPlugins";

const aiPlugins = [
  {
    id: "appsmith-ai-plugin-id",
    name: "Appsmith AI",
    type: "AI",
    packageName: PluginPackageName.APPSMITH_AI,
    iconLocation: "",
  },
  {
    id: "openai-plugin-id",
    name: "OpenAI",
    type: "AI",
    packageName: "openai-plugin",
    iconLocation: "",
  },
];

function renderWithBuiltInAi(isBuiltInAiEnabled: boolean) {
  store.dispatch(
    fetchFeatureFlagsSuccess({
      ...DEFAULT_FEATURE_FLAG_VALUE,
      fork_builtin_ai_enabled: isBuiltInAiEnabled,
    }),
  );
  store.dispatch({
    type: ReduxActionTypes.FETCH_PLUGINS_SUCCESS,
    payload: aiPlugins,
  });

  return render(
    <AIPlugins pageId="pageId" showUnsupportedPluginDialog={() => {}} />,
  );
}

describe("AIPlugins create-new list", () => {
  it("hides deprecated Appsmith AI but keeps other AI plugins", () => {
    const { container } = renderWithBuiltInAi(true);

    // BYOK AI plugins must remain creatable
    expect(
      container.querySelector(".t--createBlankApi-openai-plugin"),
    ).not.toBeNull();
    // The deprecated managed Appsmith AI plugin must not be offered for creation
    expect(
      container.querySelector(
        `.t--createBlankApi-${PluginPackageName.APPSMITH_AI}`,
      ),
    ).toBeNull();
  });

  // Fork: APPSMITH_AI_ENABLED off (the default) hides the whole AI integrations section.
  it("shows no AI integrations while the built-in AI is switched off", () => {
    const { container } = renderWithBuiltInAi(false);

    expect(container.querySelector("#new-ai-query")).toBeNull();
    expect(
      container.querySelector(".t--createBlankApi-openai-plugin"),
    ).toBeNull();
  });
});

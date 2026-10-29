import React from "react";
import { render, screen } from "@testing-library/react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import "@testing-library/jest-dom";
import type { DefaultRootState } from "react-redux";

import { getIsBuiltInGitEnabled } from "ee/selectors/builtInGitSelectors";
import { GitModals } from "./GitModals";

// Fork: built-in Git is off unless the server sends fork_builtin_git_enabled (APPSMITH_GIT_ENABLED).
jest.mock("pages/Editor/gitSync/GitSyncModal", () => ({
  __esModule: true,
  default: () => <div>git-sync-modal</div>,
}));
jest.mock("pages/Editor/gitSync/GitSettingsModal", () => ({
  __esModule: true,
  default: () => <div>git-settings-modal</div>,
}));
jest.mock("ee/components/gitComponents/DisableCDModal", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("ee/components/gitComponents/ReconfigureCDKeyModal", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("pages/Editor/gitSync/DisconnectGitModal", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("pages/Editor/gitSync/DisableAutocommitModal", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("pages/Editor/gitSync/RepoLimitExceededErrorModal", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("git", () => ({
  __esModule: true,
  GitModals: () => <div>new-git-modals</div>,
}));

const featureFlagState = (featureFlags: Record<string, boolean>) => ({
  ui: { users: { featureFlag: { data: featureFlags, overriddenFlags: {} } } },
});

const renderGitModals = (featureFlags: Record<string, boolean>) =>
  render(
    <Provider store={configureStore([])(featureFlagState(featureFlags))}>
      <GitModals />
    </Provider>,
  );

describe("built-in Git switch (fork)", () => {
  it("is off when the server does not send the flag", () => {
    const state = featureFlagState({}) as unknown as DefaultRootState;

    expect(getIsBuiltInGitEnabled(state)).toBe(false);
  });

  it("mounts no Git modal in the editor while it is off", () => {
    const { container } = renderGitModals({ fork_builtin_git_enabled: false });

    expect(container).toBeEmptyDOMElement();
  });

  it("mounts the Git modals again when it is switched on", () => {
    renderGitModals({ fork_builtin_git_enabled: true });

    expect(screen.getByText("git-sync-modal")).toBeInTheDocument();
    expect(screen.getByText("git-settings-modal")).toBeInTheDocument();
  });
});

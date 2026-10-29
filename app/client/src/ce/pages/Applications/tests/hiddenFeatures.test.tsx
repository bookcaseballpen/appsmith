import React from "react";
import { render, screen } from "@testing-library/react";
import { Provider } from "react-redux";
import { ThemeProvider } from "styled-components";
import "@testing-library/jest-dom";

import store from "store";
import { lightTheme } from "selectors/themeSelectors";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { PERMISSION_TYPE } from "ee/utils/permissionHelpers";
import type { Workspace } from "ee/constants/workspaceConstants";
import { ApplicationsSection, LeftPane } from "../index";

// Fork: the home page has one hidden workspace and no templates or built-in Git (FORK.md, "Hidden features").
// The pieces that would show them are replaced by markers, so the test sees whether the page still mounts them.
jest.mock("ee/pages/Applications/WorkspaceAction", () => ({
  __esModule: true,
  default: () => <div>create-new-menu</div>,
}));
jest.mock("ee/pages/Applications/WorkspaceMenu", () => ({
  __esModule: true,
  default: () => <div>workspace-menu</div>,
}));
jest.mock("pages/common/SharedUserList", () => ({
  __esModule: true,
  default: () => <div>workspace-members</div>,
}));
jest.mock(
  "../CreateNewAppFromTemplateModal/CreateNewAppFromTemplatesWrapper",
  () => ({
    __esModule: true,
    default: () => <div>templates-modal</div>,
  }),
);
jest.mock("pages/Editor/gitSync/GitSyncModal", () => ({
  __esModule: true,
  default: () => <div>git-import-modal</div>,
}));
jest.mock("ee/pages/Applications/PackageCardList", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("ee/pages/Applications/WorkflowCardList", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("pages/common/ImportModal", () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock("pages/Editor/gitSync/ReconnectDatasourceModal", () => ({
  __esModule: true,
  default: () => null,
}));
// ce/pages/Applications/ApplicationCardList imports NoAppsFound back out of ee/pages/Applications (a require cycle);
// stubbing the barrel breaks it, as ApplicationsSection.test.tsx does.
jest.mock("ee/pages/Applications", () => ({
  __esModule: true,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  NoAppsFound: ({ children }: any) => <div>{children}</div>,
}));
jest.mock("pages/Applications/ApplicationCard", () => ({
  __esModule: true,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  default: ({ application }: any) => <div>{application.name}</div>,
}));

const workspace = {
  id: "apps-workspace",
  name: "Apps",
  userPermissions: [
    PERMISSION_TYPE.MANAGE_WORKSPACE,
    PERMISSION_TYPE.INVITE_USER_TO_WORKSPACE,
    PERMISSION_TYPE.CREATE_APPLICATION,
  ],
} as unknown as Workspace;

const renderWithStore = (children: React.ReactNode) =>
  render(
    <ThemeProvider theme={lightTheme}>
      <Provider store={store}>{children}</Provider>
    </ThemeProvider>,
  );

describe("Home page (fork: hidden workspace, templates and Git)", () => {
  beforeAll(() => {
    // A signed-in admin who could manage and invite to the workspace in upstream.
    store.dispatch({
      type: ReduxActionTypes.FETCH_USER_DETAILS_SUCCESS,
      payload: {
        email: "admin@example.com",
        username: "admin@example.com",
        name: "Admin",
        isSuperUser: true,
        role: "ADMIN",
        defaultWorkspaceId: workspace.id,
      },
    });
  });

  it("shows no workspace list or 'create workspace'", () => {
    const { container } = renderWithStore(
      <LeftPane
        activeWorkspaceId={workspace.id}
        isFetchingOrganizations={false}
        isFetchingWorkspaces={false}
        organizations={[]}
        workspaces={[workspace]}
      />,
    );

    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByText("Workspaces")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("t--workspace-new-workspace-auto-create"),
    ).not.toBeInTheDocument();
  });

  it("lists the apps with the create menu, but no workspace menu, members, templates or Git import", () => {
    renderWithStore(
      <ApplicationsSection
        activeWorkspaceId={workspace.id}
        applications={[
          { id: "app-1", name: "Sales report", workspaceId: workspace.id },
        ]}
        packages={[]}
        workflows={[]}
        workspaces={[workspace]}
      />,
    );

    expect(screen.getByText("Sales report")).toBeInTheDocument();
    expect(screen.getByText("create-new-menu")).toBeInTheDocument();
    expect(screen.queryByText("workspace-menu")).not.toBeInTheDocument();
    expect(screen.queryByText("workspace-members")).not.toBeInTheDocument();
    expect(screen.queryByText("templates-modal")).not.toBeInTheDocument();
    expect(screen.queryByText("git-import-modal")).not.toBeInTheDocument();
  });
});

import React from "react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "styled-components";
import { lightTheme } from "selectors/themeSelectors";

import ApprovalApi from "ee/api/ApprovalApi";
import ReleaseControls from "./ReleaseControls";

jest.mock("ee/api/ApprovalApi");

jest.mock("selectors/editorSelectors", () => ({
  getCurrentApplicationId: () => "app-1",
}));

jest.mock("pages/AppIDE/layouts/components/Header/DeployButton", () => ({
  __esModule: true,
  default: () => <button data-testid="deploy-button">Deploy</button>,
}));

const mockedApi = ApprovalApi as jest.Mocked<typeof ApprovalApi>;

const ok = (data: unknown) =>
  ({ responseMeta: { success: true }, data }) as never;

const status = (overrides: object = {}) => ({
  applicationId: "app-1",
  applicationName: "Orders",
  workspaceId: "ws-1",
  status: "DRAFT",
  history: [],
  ...overrides,
});

const renderAs = (role: string) =>
  render(
    <Provider
      store={configureStore([])({
        ui: { users: { currentUser: { email: "someone@test", role } } },
      })}
    >
      <ThemeProvider theme={lightTheme}>
        <ReleaseControls />
      </ThemeProvider>
    </Provider>,
  );

describe("ReleaseControls", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    // The status popover positions itself with a ResizeObserver, which jsdom lacks.
    window.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
  });

  it("shows Submit for approval instead of Deploy to a Dev", async () => {
    mockedApi.fetchStatus.mockResolvedValue(ok(status()));

    renderAs("DEV");

    expect(await screen.findByText("Submit for approval")).toBeTruthy();
    expect(screen.queryByTestId("deploy-button")).toBeNull();
    expect((await screen.findByTestId("t--approval-status-chip")).textContent)
      .toBe("Draft");
  });

  it.each(["APPROVER", "ADMIN"])("shows Deploy to %s", async (role) => {
    mockedApi.fetchStatus.mockResolvedValue(
      ok(status({ status: "SUBMITTED" })),
    );

    renderAs(role);

    expect(await screen.findByTestId("deploy-button")).toBeTruthy();
    expect(screen.queryByText("Submit for approval")).toBeNull();
    expect(
      (await screen.findByTestId("t--approval-status-chip")).textContent,
    ).toBe("Waiting for approval");
  });

  it("submits the app and shows it waiting", async () => {
    mockedApi.fetchStatus.mockResolvedValue(ok(status()));
    mockedApi.submit.mockResolvedValue(ok(status({ status: "SUBMITTED" })));

    renderAs("DEV");
    await screen.findByTestId("t--approval-status-chip");
    fireEvent.click(screen.getByTestId("t--submit-for-approval-btn"));

    await waitFor(() =>
      expect(screen.getByTestId("t--approval-status-chip").textContent).toBe(
        "Waiting for approval",
      ),
    );
    expect(mockedApi.submit).toHaveBeenCalledWith("app-1");
  });

  it("shows the Approver's comment on a rejected app", async () => {
    mockedApi.fetchStatus.mockResolvedValue(
      ok(
        status({
          status: "REJECTED",
          reviewedBy: "approver@example.com",
          comment: "Fix the title",
        }),
      ),
    );

    renderAs("DEV");
    fireEvent.click(await screen.findByTestId("t--approval-status-chip"));

    expect(
      (await screen.findByTestId("t--approval-reject-comment")).textContent,
    ).toBe("approver@example.com: Fix the title");
  });
});

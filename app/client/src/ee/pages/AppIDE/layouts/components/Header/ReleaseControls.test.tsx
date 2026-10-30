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
    expect(screen.queryByText(/deploy/i)).toBeNull();
    expect(
      (await screen.findByTestId("t--approval-status-chip")).textContent,
    ).toBe("Draft");
  });

  it.each(["APPROVER", "ADMIN"])(
    "shows Submit and Approve, never Deploy, to %s",
    async (role) => {
      mockedApi.fetchStatus.mockResolvedValue(
        ok(status({ status: "SUBMITTED" })),
      );

      renderAs(role);

      expect(await screen.findByTestId("t--approve-release-btn")).toBeTruthy();
      expect(screen.getByText("Submit for approval")).toBeTruthy();
      expect(screen.queryByText(/deploy/i)).toBeNull();
      expect(screen.getByTestId("t--approval-status-chip").textContent).toBe(
        "Waiting for approval",
      );
    },
  );

  it("approves a waiting release, which publishes it", async () => {
    mockedApi.fetchStatus.mockResolvedValue(
      ok(status({ status: "SUBMITTED" })),
    );
    mockedApi.approve.mockResolvedValue(ok(status({ status: "APPROVED" })));

    renderAs("ADMIN");
    fireEvent.click(await screen.findByTestId("t--approve-release-btn"));

    await waitFor(() =>
      expect(screen.getByTestId("t--approval-status-chip").textContent).toBe(
        "Approved",
      ),
    );
    expect(mockedApi.approve).toHaveBeenCalledWith("app-1");
    expect(screen.queryByTestId("t--approve-release-btn")).toBeNull();
  });

  it("does not offer Approve to a Dev", async () => {
    mockedApi.fetchStatus.mockResolvedValue(
      ok(status({ status: "SUBMITTED" })),
    );

    renderAs("DEV");
    await screen.findByTestId("t--approval-status-chip");

    expect(screen.queryByTestId("t--approve-release-btn")).toBeNull();
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

  it("shows the submitter's note in the history", async () => {
    mockedApi.fetchStatus.mockResolvedValue(
      ok(
        status({
          status: "SUBMITTED",
          history: [
            {
              type: "SUBMITTED",
              by: "dev@example.com",
              at: 1793268000,
              comment: "New order filter",
            },
          ],
        }),
      ),
    );

    renderAs("APPROVER");
    fireEvent.click(await screen.findByTestId("t--approval-status-chip"));

    expect(await screen.findByText(/"New order filter"/)).toBeTruthy();
  });
});

import React from "react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "styled-components";
import { lightTheme } from "selectors/themeSelectors";

import ApprovalApi from "ee/api/ApprovalApi";
import PendingApprovals from "./PendingApprovals";

jest.mock("ee/api/ApprovalApi");

const mockedApi = ApprovalApi as jest.Mocked<typeof ApprovalApi>;

const ok = (data: unknown) =>
  ({ responseMeta: { success: true }, data }) as never;

const waiting = {
  applicationId: "app-1",
  applicationName: "Orders",
  workspaceId: "ws-1",
  defaultBasePageId: "page-1",
  status: "SUBMITTED",
  version: "abcdef123456",
  submittedBy: "dev@example.com",
  submittedAt: "2026-10-29T10:00:00Z",
  history: [],
};

const renderAs = (role: string) =>
  render(
    <Provider
      store={configureStore([])({
        ui: { users: { currentUser: { email: "someone@test", role } } },
      })}
    >
      <ThemeProvider theme={lightTheme}>
        <PendingApprovals />
      </ThemeProvider>
    </Provider>,
  );

describe("PendingApprovals", () => {
  beforeEach(() => {
    jest.resetAllMocks();
  });

  it.each(["DEV", "USER"])("renders nothing for %s", (role) => {
    const { container } = renderAs(role);

    expect(container.innerHTML).toBe("");
    expect(mockedApi.fetchPending).not.toHaveBeenCalled();
  });

  it("lists waiting releases with a count badge and a preview link", async () => {
    mockedApi.fetchPending.mockResolvedValue(ok([waiting]));

    renderAs("APPROVER");

    expect(await screen.findByText("Orders")).toBeTruthy();
    expect(screen.getByTestId("t--pending-approvals-count").textContent).toBe(
      "1",
    );
    expect(screen.getByText("Preview").closest("a")?.getAttribute("href")).toBe(
      "/app/application/page-page-1/edit",
    );
  });

  it("approves and reloads the list", async () => {
    mockedApi.fetchPending
      .mockResolvedValueOnce(ok([waiting]))
      .mockResolvedValueOnce(ok([]));
    mockedApi.approve.mockResolvedValue(ok({ ...waiting, status: "APPROVED" }));

    renderAs("ADMIN");
    fireEvent.click(await screen.findByTestId("t--approve-release-btn"));

    await waitFor(() =>
      expect(screen.getByTestId("t--pending-approvals-count").textContent).toBe(
        "0",
      ),
    );
    expect(mockedApi.approve).toHaveBeenCalledWith("app-1");
  });

  it("drops a list that arrives after the page was left", async () => {
    let answer: (value: never) => void = () => undefined;

    mockedApi.fetchPending.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve;
      }) as never,
    );
    const consoleError = jest
      .spyOn(console, "error")
      .mockImplementation(() => undefined);

    const { unmount } = renderAs("APPROVER");

    unmount();
    answer(ok([waiting]));
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockedApi.fetchPending).toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it("needs a comment to reject", async () => {
    mockedApi.fetchPending.mockResolvedValue(ok([waiting]));
    mockedApi.reject.mockResolvedValue(ok({ ...waiting, status: "REJECTED" }));

    renderAs("APPROVER");
    fireEvent.click(await screen.findByTestId("t--reject-release-btn"));
    const confirm = await screen.findByTestId("t--confirm-reject-btn");

    expect(confirm.hasAttribute("disabled")).toBe(true);

    fireEvent.change(screen.getByTestId("t--reject-comment-input"), {
      target: { value: "  Fix the title " },
    });
    fireEvent.click(screen.getByTestId("t--confirm-reject-btn"));

    await waitFor(() =>
      expect(mockedApi.reject).toHaveBeenCalledWith("app-1", "Fix the title"),
    );
  });
});

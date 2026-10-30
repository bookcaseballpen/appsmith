import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "styled-components";
import "@testing-library/jest-dom";
import { lightTheme } from "selectors/themeSelectors";
import { toast } from "@appsmith/ads";

import TeamsApi from "ee/api/TeamsApi";
import InstanceRoleApi from "ee/api/InstanceRoleApi";
import AppAccessModal from "./AppAccessModal";

jest.mock("ee/api/TeamsApi");
jest.mock("ee/api/InstanceRoleApi");

const mockedTeams = TeamsApi as jest.Mocked<typeof TeamsApi>;
const mockedUsers = InstanceRoleApi as jest.Mocked<typeof InstanceRoleApi>;

const ok = (data: unknown) =>
  ({ responseMeta: { success: true }, data }) as never;

const teamGrant = {
  grantee: { type: "TEAM", id: "team-1" },
  name: "Finance",
  level: "VIEW",
};

const renderModal = () =>
  render(
    <ThemeProvider theme={lightTheme}>
      <AppAccessModal
        applicationId="app-1"
        applicationName="Finance Dashboard"
        onClose={jest.fn()}
      />
    </ThemeProvider>,
  );

describe("AppAccessModal", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockedTeams.fetchGrants.mockResolvedValue(ok([teamGrant]));
    mockedTeams.fetchTeams.mockResolvedValue(
      ok([{ id: "team-1", name: "Finance", members: [] }]),
    );
    mockedUsers.fetchUsers.mockResolvedValue(
      ok([
        { id: "u-admin", email: "dev-admin@example.com", role: "ADMIN" },
        { id: "u-dev", email: "dev@example.com", role: "DEV" },
        { id: "u-user", email: "user@example.com", role: "USER" },
      ]),
    );
  });

  it("lists who holds access", async () => {
    renderModal();

    expect(await screen.findByText("Team: Finance")).toBeInTheDocument();
    expect(mockedTeams.fetchGrants).toHaveBeenCalledWith("app-1");
  });

  it("removes a grant by setting it to NONE", async () => {
    mockedTeams.setGrant.mockResolvedValue(ok([]));
    renderModal();

    fireEvent.click(await screen.findByTestId("t--app-grant-remove"));

    await waitFor(() =>
      expect(mockedTeams.setGrant).toHaveBeenCalledWith(
        "app-1",
        { type: "TEAM", id: "team-1" },
        "NONE",
      ),
    );
    expect(
      await screen.findByText("Nobody holds access to this app yet."),
    ).toBeInTheDocument();
  });

  it("shows the server's message when a change is refused", async () => {
    mockedTeams.setGrant.mockResolvedValue({
      responseMeta: { success: false, error: { message: "Not allowed" } },
      data: null,
    } as never);
    const show = jest.spyOn(toast, "show").mockImplementation(jest.fn());

    renderModal();

    fireEvent.click(await screen.findByTestId("t--app-grant-remove"));

    await waitFor(() =>
      expect(show).toHaveBeenCalledWith("Not allowed", { kind: "error" }),
    );
    expect(screen.getByText("Team: Finance")).toBeInTheDocument();
    show.mockRestore();
  });
});

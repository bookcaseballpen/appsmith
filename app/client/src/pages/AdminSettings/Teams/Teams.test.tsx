import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "styled-components";
import "@testing-library/jest-dom";
import { lightTheme } from "selectors/themeSelectors";

import TeamsApi from "ee/api/TeamsApi";
import InstanceRoleApi from "ee/api/InstanceRoleApi";
import TeamsSettings, { getTeamNameError } from "./index";

jest.mock("ee/api/TeamsApi");
jest.mock("ee/api/InstanceRoleApi");

const mockedTeams = TeamsApi as jest.Mocked<typeof TeamsApi>;
const mockedUsers = InstanceRoleApi as jest.Mocked<typeof InstanceRoleApi>;

const ok = (data: unknown) =>
  ({ responseMeta: { success: true }, data }) as never;

const dev = { id: "u-dev", email: "dev@example.com", role: "DEV" };
const finance = {
  id: "team-1",
  name: "Finance",
  description: "Money people",
  members: [dev],
};

const renderPage = () =>
  render(
    <ThemeProvider theme={lightTheme}>
      <TeamsSettings />
    </ThemeProvider>,
  );

describe("Teams admin page", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockedTeams.fetchTeams.mockResolvedValue(ok([finance]));
    mockedUsers.fetchUsers.mockResolvedValue(
      ok([dev, { id: "u-user", email: "user@example.com", role: "USER" }]),
    );
  });

  it("lists the teams with their members", async () => {
    renderPage();

    expect(await screen.findByText("Finance")).toBeInTheDocument();
    expect(screen.getByText("Money people")).toBeInTheDocument();
    expect(
      screen.getByTestId("t--team-member-dev@example.com"),
    ).toBeInTheDocument();
  });

  it("creates a team", async () => {
    mockedTeams.createTeam.mockResolvedValue(
      ok({ id: "team-2", name: "Sales", members: [] }),
    );
    renderPage();
    await screen.findByText("Finance");

    fireEvent.change(screen.getByTestId("t--team-name-input"), {
      target: { value: "  Sales " },
    });
    fireEvent.click(screen.getByTestId("t--team-create"));

    expect(await screen.findByText("Sales")).toBeInTheDocument();
    expect(mockedTeams.createTeam).toHaveBeenCalledWith("Sales", undefined);
  });

  it("renames a team", async () => {
    mockedTeams.updateTeam.mockResolvedValue(
      ok({ ...finance, name: "Finance EU" }),
    );
    renderPage();

    fireEvent.click(await screen.findByTestId("t--team-rename"));
    fireEvent.change(screen.getByTestId("t--team-rename-input"), {
      target: { value: "Finance EU" },
    });
    fireEvent.click(screen.getByTestId("t--team-rename-save"));

    expect(await screen.findByText("Finance EU")).toBeInTheDocument();
    expect(mockedTeams.updateTeam).toHaveBeenCalledWith(
      "team-1",
      "Finance EU",
      "Money people",
    );
  });

  it("deletes a team after a confirmation", async () => {
    mockedTeams.deleteTeam.mockResolvedValue(ok(true));
    renderPage();

    fireEvent.click(await screen.findByTestId("t--team-delete"));
    expect(mockedTeams.deleteTeam).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("t--team-delete-confirm"));

    await waitFor(() =>
      expect(screen.queryByText("Finance")).not.toBeInTheDocument(),
    );
    expect(mockedTeams.deleteTeam).toHaveBeenCalledWith("team-1");
  });

  it("removes a member", async () => {
    mockedTeams.removeMember.mockResolvedValue(ok({ ...finance, members: [] }));
    renderPage();

    fireEvent.click(await screen.findByTestId("t--team-member-remove"));

    expect(await screen.findByText("No members yet.")).toBeInTheDocument();
    expect(mockedTeams.removeMember).toHaveBeenCalledWith("team-1", "u-dev");
  });

  it("refuses a name over the server's limit", () => {
    expect(getTeamNameError("x".repeat(100))).toBeUndefined();
    expect(getTeamNameError("x".repeat(101))).toBe(
      "A team name has at most 100 characters",
    );
  });
});

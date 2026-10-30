import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { ThemeProvider } from "styled-components";
import "@testing-library/jest-dom";
import { lightTheme } from "selectors/themeSelectors";
import { toast } from "@appsmith/ads";

import CatalogApi from "ee/api/CatalogApi";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import AppTagsSetting, { getTagError } from "./AppTagsSetting";

jest.mock("ee/api/CatalogApi");

const mockedApi = CatalogApi as jest.Mocked<typeof CatalogApi>;

const ok = (data: unknown) =>
  ({ responseMeta: { success: true }, data }) as never;

const renderAs = (role: string, userPermissions: string[]) => {
  const store = configureStore([])({
    ui: {
      users: { currentUser: { email: "someone@example.com", role } },
      applications: {
        currentApplication: {
          id: "app-1",
          tags: ["finance"],
          userPermissions,
        },
      },
    },
  });

  render(
    <Provider store={store}>
      <ThemeProvider theme={lightTheme}>
        <AppTagsSetting />
      </ThemeProvider>
    </Provider>,
  );

  return store;
};

const input = () =>
  document.getElementById(
    "t--general-settings-app-tags-input",
  ) as HTMLInputElement;

describe("AppTagsSetting", () => {
  beforeEach(() => jest.resetAllMocks());

  it("lets a Dev with Edit add a tag, normalised as the server stores it", async () => {
    mockedApi.setTags.mockResolvedValue(
      ok({ applicationId: "app-1", tags: ["finance", "month end"] }),
    );
    const store = renderAs("DEV", ["manage:applications"]);

    fireEvent.change(input(), { target: { value: "  Month   End " } });
    fireEvent.keyDown(input(), { key: "Enter" });

    await waitFor(() =>
      expect(mockedApi.setTags).toHaveBeenCalledWith("app-1", [
        "finance",
        "month end",
      ]),
    );
    expect(store.getActions()).toContainEqual({
      type: ReduxActionTypes.CURRENT_APPLICATION_TAGS_UPDATE,
      payload: ["finance", "month end"],
    });
  });

  it("removes a tag", async () => {
    mockedApi.setTags.mockResolvedValue(
      ok({ applicationId: "app-1", tags: [] }),
    );
    renderAs("ADMIN", ["manage:applications"]);

    // The close icon is the tag's only button.
    fireEvent.click(
      screen.getByTestId("t--app-tag-finance").querySelector("button")!,
    );

    await waitFor(() =>
      expect(mockedApi.setTags).toHaveBeenCalledWith("app-1", []),
    );
  });

  it("puts the tags back and shows the server's message when the save fails", async () => {
    const show = jest.spyOn(toast, "show").mockImplementation(jest.fn());

    mockedApi.setTags.mockResolvedValue({
      responseMeta: { success: false, error: { message: "Forbidden" } },
      data: null,
    } as never);
    renderAs("DEV", ["manage:applications"]);

    fireEvent.change(input(), { target: { value: "hr" } });
    fireEvent.keyDown(input(), { key: "Enter" });

    await waitFor(() =>
      expect(show).toHaveBeenCalledWith("Forbidden", { kind: "error" }),
    );
    expect(screen.queryByTestId("t--app-tag-hr")).not.toBeInTheDocument();
    show.mockRestore();
  });

  it.each([
    ["an Approver", "APPROVER", ["manage:applications"]],
    ["a Dev without Edit", "DEV", ["read:applications"]],
  ])("shows %s the tags only", (_, role, permissions) => {
    renderAs(role, permissions);

    expect(screen.getByText("finance")).toBeInTheDocument();
    expect(input()).toBeNull();
  });

  it("refuses tags over the server's limits", () => {
    const ten = Array.from({ length: 10 }, (_, i) => `t${i}`);

    expect(getTagError("x".repeat(30), [])).toBeUndefined();
    expect(getTagError("x".repeat(31), [])).toBe(
      "A tag has at most 30 characters",
    );
    expect(getTagError("new", ten)).toBe("An app has at most 10 tags");
    expect(getTagError("t1", ten)).toBeUndefined();
  });
});

import React from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { Provider } from "react-redux";
import { ThemeProvider } from "styled-components";
import "@testing-library/jest-dom";

import store from "store";
import { lightTheme } from "selectors/themeSelectors";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import type { CatalogItem } from "ee/api/CatalogApi";
import CatalogSection from "./CatalogSection";

jest.mock("./AppAccessModal", () => ({
  __esModule: true,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  default: ({ applicationName }: any) => (
    <div>access-modal {applicationName}</div>
  ),
}));

const item = (overrides: Partial<CatalogItem>): CatalogItem => ({
  id: "app-1",
  baseId: "app-1",
  workspaceId: "ws-1",
  name: "Finance Dashboard",
  slug: "finance-dashboard",
  tags: ["finance"],
  defaultPageId: "6720aa0000000000000000p1".replace("p1", "01"),
  defaultPageSlug: "page1",
  canEdit: false,
  ...overrides,
});

const signIn = (role: string) =>
  store.dispatch({
    type: ReduxActionTypes.FETCH_USER_DETAILS_SUCCESS,
    payload: {
      email: `${role.toLowerCase()}@example.com`,
      username: `${role.toLowerCase()}@example.com`,
      name: role,
      role,
    },
  });

const showCatalog = (items: CatalogItem[], total = items.length) => {
  store.dispatch({
    type: ReduxActionTypes.FETCH_CATALOG_SUCCESS,
    payload: { items, total, page: 0, size: 24 },
  });
  store.dispatch({
    type: ReduxActionTypes.FETCH_CATALOG_TAGS_SUCCESS,
    payload: ["finance", "hr"],
  });
};

const renderCatalog = () =>
  render(
    <ThemeProvider theme={lightTheme}>
      <Provider store={store}>
        <CatalogSection
          deleteApplication={jest.fn()}
          enableImportExport
          title="Applications"
          updateApplicationDispatch={jest.fn()}
          workspaceId="ws-1"
        />
      </Provider>
    </ThemeProvider>,
  );

const hoverCard = (name: string) => fireEvent.mouseOver(screen.getByText(name));

describe("CatalogSection", () => {
  let dispatch: jest.SpyInstance;

  beforeEach(() => {
    dispatch = jest.spyOn(store, "dispatch");
  });

  afterEach(() => {
    dispatch.mockRestore();
    store.dispatch({
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { search: "", tags: [], tagMode: "any", sort: "name" },
    });
  });

  it("asks the server for the catalog and the tags when it opens", () => {
    signIn("USER");
    renderCatalog();

    expect(dispatch).toHaveBeenCalledWith({
      type: ReduxActionTypes.FETCH_CATALOG_INIT,
    });
    expect(dispatch).toHaveBeenCalledWith({
      type: ReduxActionTypes.FETCH_CATALOG_TAGS_INIT,
    });
  });

  it("gives a User Launch only, and shows the app's tags", () => {
    signIn("USER");
    renderCatalog();
    showCatalog([item({})]);

    hoverCard("Finance Dashboard");

    expect(screen.getByText("Launch")).toBeInTheDocument();
    expect(screen.queryByText("Edit")).not.toBeInTheDocument();
    expect(
      within(screen.getByTestId("t--catalog-card-tags")).getByText("finance"),
    ).toBeInTheDocument();
  });

  it("shows Edit only on the apps the server says can be edited", () => {
    signIn("DEV");
    renderCatalog();
    showCatalog([
      item({ id: "a", name: "Editable", canEdit: true }),
      item({ id: "b", name: "Viewable", canEdit: false }),
    ]);

    hoverCard("Editable");
    expect(screen.getByText("Edit")).toBeInTheDocument();

    fireEvent.mouseLeave(screen.getByText("Editable"));
    hoverCard("Viewable");
    expect(screen.queryByText("Edit")).not.toBeInTheDocument();
    expect(screen.getByText("Launch")).toBeInTheDocument();
  });

  it("searches and filters by tag through the store", () => {
    signIn("DEV");
    renderCatalog();
    showCatalog([item({})]);

    fireEvent.change(screen.getByTestId("t--catalog-search"), {
      target: { value: "fin" },
    });
    expect(dispatch).toHaveBeenCalledWith({
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { search: "fin" },
    });

    fireEvent.click(screen.getByTestId("t--catalog-tag-hr"));
    expect(dispatch).toHaveBeenCalledWith({
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { tags: ["hr"] },
    });
    expect(screen.getByTestId("t--catalog-tag-hr")).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("offers any/all once two tags are selected", () => {
    signIn("DEV");
    renderCatalog();
    showCatalog([item({})]);

    fireEvent.click(screen.getByTestId("t--catalog-tag-hr"));
    expect(screen.queryByTestId("t--catalog-tag-mode")).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId("t--catalog-tag-finance"));
    expect(screen.getByTestId("t--catalog-tag-mode")).toBeInTheDocument();
  });

  it("pages through a long catalog", () => {
    signIn("USER");
    renderCatalog();
    showCatalog([item({})], 50);

    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Next"));
    expect(dispatch).toHaveBeenCalledWith({
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { page: 1 },
    });
  });

  it("says so when nothing matches", () => {
    signIn("USER");
    renderCatalog();
    store.dispatch({
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { search: "zzz" },
    });
    showCatalog([]);

    expect(screen.getByTestId("t--catalog-empty")).toHaveTextContent(
      "No apps match the search or the selected tags.",
    );
  });

  it("gives an Admin an Access item on the card menu", () => {
    signIn("ADMIN");
    renderCatalog();
    showCatalog([item({ canEdit: true })]);

    // Radix opens the menu on keyboard activation in jsdom.
    fireEvent.keyDown(screen.getByTestId("t--application-card-context-menu"), {
      key: "Enter",
    });
    fireEvent.click(screen.getByTestId("t--app-access"));

    expect(
      screen.getByText("access-modal Finance Dashboard"),
    ).toBeInTheDocument();
  });

  it("gives a Dev no Access item", () => {
    signIn("DEV");
    renderCatalog();
    showCatalog([item({ canEdit: true })]);

    fireEvent.keyDown(screen.getByTestId("t--application-card-context-menu"), {
      key: "Enter",
    });

    // The menu is open (the Dev may edit the app's name), but has no Access item.
    expect(document.querySelector(".t--application-name")).toBeInTheDocument();
    expect(screen.queryByTestId("t--app-access")).not.toBeInTheDocument();
  });
});

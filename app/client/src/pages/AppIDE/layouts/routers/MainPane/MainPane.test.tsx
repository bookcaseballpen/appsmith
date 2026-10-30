import React from "react";
import "@testing-library/jest-dom";
import { render, screen } from "@testing-library/react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { MemoryRouter, Route } from "react-router";
import { MainPane } from "./MainPane";

// Fork: in the read-only editor the main pane runs the canvas only, but Admins still get the datasource pages
// (FORK.md, "Read-only editor"). The real routes and the canvas are replaced by markers.
jest.mock("ee/pages/AppIDE/layouts/routers/MainPane/constants", () => ({
  MainPaneRoutes: (path: string) => [
    {
      key: "Canvas",
      exact: true,
      path: [path, `${path}/queries`],
      render: () => <div>canvas-route</div>,
    },
    {
      key: "DatasourceEditor",
      exact: true,
      path: `${path}/datasource/:datasourceId`,
      render: () => <div>datasource-page</div>,
    },
    {
      key: "Datasource Create and Active",
      exact: true,
      path: `${path}/datasources/:selectedTab`,
      render: () => <div>new-datasource-page</div>,
    },
  ],
}));
jest.mock("pages/Editor/WidgetsEditor", () => ({
  __esModule: true,
  default: () => <div>read-only-canvas</div>,
}));
jest.mock("../../hooks/useWidgetSelectionBlockListener", () => ({
  useWidgetSelectionBlockListener: jest.fn(),
}));
jest.mock("components/SentryRoute", () => ({
  SentryRoute: jest.requireActual("react-router").Route,
}));

const mockCanvasOnly = jest.fn(() => false);
const mockPreview = jest.fn(() => true);

jest.mock("selectors/gitModSelectors", () => ({
  selectCanvasOnlyMode: () => mockCanvasOnly(),
  selectCombinedPreviewMode: () => mockPreview(),
}));

const EDITOR = "/app/sales/page1-abc/edit";

const renderAt = (url: string, role: string) =>
  render(
    <Provider
      store={configureStore([])({
        ui: { users: { currentUser: { email: "a@example.com", role } } },
      })}
    >
      <MemoryRouter initialEntries={[url]}>
        <Route path={EDITOR}>
          <MainPane id="main" />
        </Route>
      </MemoryRouter>
    </Provider>,
  );

describe("MainPane in the read-only editor", () => {
  beforeEach(() => {
    mockPreview.mockReturnValue(true);
    mockCanvasOnly.mockReturnValue(false);
  });

  it("opens a datasource page for an Admin", () => {
    renderAt(`${EDITOR}/datasource/ds-1`, "ADMIN");

    expect(screen.getByText("datasource-page")).toBeInTheDocument();
  });

  it("opens the new datasource page for an Admin", () => {
    renderAt(`${EDITOR}/datasources/NEW`, "ADMIN");

    expect(screen.getByText("new-datasource-page")).toBeInTheDocument();
  });

  it("keeps the read-only canvas for an Admin everywhere else", () => {
    renderAt(`${EDITOR}/queries`, "ADMIN");

    expect(screen.getByText("read-only-canvas")).toBeInTheDocument();
    expect(screen.queryByText("canvas-route")).not.toBeInTheDocument();
  });

  it.each(["DEV", "APPROVER"])(
    "keeps the read-only canvas for a %s on a datasource page",
    (role) => {
      renderAt(`${EDITOR}/datasource/ds-1`, role);

      expect(screen.getByText("read-only-canvas")).toBeInTheDocument();
      expect(screen.queryByText("datasource-page")).not.toBeInTheDocument();
    },
  );

  it("keeps the canvas only in preview mode, for an Admin too", () => {
    mockCanvasOnly.mockReturnValue(true);

    renderAt(`${EDITOR}/datasource/ds-1`, "ADMIN");

    expect(screen.getByText("read-only-canvas")).toBeInTheDocument();
  });

  it("routes as upstream when the editor is not read-only", () => {
    mockPreview.mockReturnValue(false);

    renderAt(`${EDITOR}/queries`, "DEV");

    expect(screen.getByText("canvas-route")).toBeInTheDocument();
  });
});

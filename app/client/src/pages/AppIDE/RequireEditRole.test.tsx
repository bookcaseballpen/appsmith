import React from "react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { render } from "@testing-library/react";
import { MemoryRouter, Route, Switch } from "react-router-dom";

import RequireEditRole from "./RequireEditRole";

jest.mock("./AppIDELoader", () => ({
  __esModule: true,
  default: () => <div data-testid="app-ide" />,
}));

const mockStore = configureStore([]);

const renderAt = (path: string, role?: string) =>
  render(
    <Provider
      store={mockStore({
        ui: { users: { currentUser: { email: "someone@test", role } } },
      })}
    >
      <MemoryRouter initialEntries={[path]}>
        <Switch>
          <Route component={RequireEditRole} path="/app/:appSlug/:page/edit" />
          <Route
            path="/app/:appSlug/:page"
            render={({ location }) => (
              <div data-testid="viewer">
                {location.pathname}
                {location.search}
              </div>
            )}
          />
        </Switch>
      </MemoryRouter>
    </Provider>,
  );

describe("RequireEditRole", () => {
  it("sends a User to the same page in view mode", () => {
    const { getByTestId, queryByTestId } = renderAt(
      "/app/my-app/page1-abc/edit/queries/q1?branch=main",
      "USER",
    );

    expect(queryByTestId("app-ide")).toBeNull();
    expect(getByTestId("viewer").textContent).toBe(
      "/app/my-app/page1-abc?branch=main",
    );
  });

  it.each(["ADMIN", "DEV", "APPROVER", undefined])(
    "opens the editor for role %s",
    (role) => {
      const { getByTestId } = renderAt("/app/my-app/page1-abc/edit", role);

      expect(getByTestId("app-ide")).toBeTruthy();
    },
  );
});

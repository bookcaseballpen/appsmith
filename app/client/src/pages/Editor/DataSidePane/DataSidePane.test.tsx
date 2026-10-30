import React from "react";
import "@testing-library/jest-dom";
import { screen } from "@testing-library/react";
import { DataSidePane } from "./DataSidePane";
import { datasourceFactory } from "test/factories/DatasourceFactory";
import { getIDETestState } from "test/factories/AppIDEFactoryUtils";
import { PostgresFactory } from "test/factories/Actions/Postgres";
import type { DefaultRootState } from "react-redux";
import { render } from "test/testUtils";
import { getDatasourceUsageCountForApp } from "ee/selectors/entitiesSelector";
import { IDE_TYPE } from "ee/IDE/Interfaces/IDETypes";

const productsDS = datasourceFactory().build({
  name: "Products",
  id: "products-ds-id",
});
const usersDS = datasourceFactory().build({ name: "Users", id: "users-ds-id" });
const ordersDS = datasourceFactory().build({
  name: "Orders",
  id: "orders-ds-id",
});
const usersAction1 = PostgresFactory.build({
  datasource: {
    id: usersDS.id,
  },
});
const usersAction2 = PostgresFactory.build({
  datasource: {
    id: usersDS.id,
  },
});
const ordersAction1 = PostgresFactory.build({
  datasource: {
    id: ordersDS.id,
  },
});

describe("DataSidePane", () => {
  it("renders the ds and count by using the dsUsageMap passed as props", () => {
    const state = getIDETestState({
      actions: [usersAction1, usersAction2, ordersAction1],
      datasources: [productsDS, usersDS, ordersDS],
    }) as DefaultRootState;

    const dsUsageMap = getDatasourceUsageCountForApp(state, IDE_TYPE.App);

    render(<DataSidePane dsUsageMap={dsUsageMap} />, {
      url: "/app/untitled-application-1/page1-678a356f18346f618bc2c80a/edit/datasource/users-ds-id",
      initialState: state,
    });

    expect(screen.getByText("Databases")).toBeInTheDocument();

    expect(screen.getAllByRole("listitem")).toHaveLength(3);

    expect(screen.getAllByRole("listitem")[0].textContent).toContain(
      "Products",
    );
    expect(screen.getAllByRole("listitem")[0].textContent).toContain(
      "No queries in this app",
    );

    expect(screen.getAllByRole("listitem")[1].textContent).toContain("Users");
    expect(screen.getAllByRole("listitem")[1].textContent).toContain(
      "2 queries in this app",
    );

    expect(screen.getAllByRole("listitem")[2].textContent).toContain("Orders");
    expect(screen.getAllByRole("listitem")[2].textContent).toContain(
      "1 queries in this app",
    );
  });

  // Fork: says where datasources are changed, by role (ee/components/DatasourceManagedNote).
  describe("where datasources are changed", () => {
    const renderPane = (
      role: string,
      datasources: (typeof productsDS)[],
      workspacePermissions: string[],
    ) => {
      const state = getIDETestState({ datasources }) as DefaultRootState;

      state.ui.users.currentUser = {
        ...state.ui.users.currentUser,
        email: "someone@example.com",
        role,
      } as DefaultRootState["ui"]["users"]["currentUser"];
      state.ui.selectedWorkspace.workspace = {
        ...state.ui.selectedWorkspace.workspace,
        userPermissions: workspacePermissions,
      };

      return render(<DataSidePane dsUsageMap={{}} />, {
        url: "/app/untitled-application-1/page1-678a356f18346f618bc2c80a/edit/datasource",
        initialState: state,
      });
    };

    it("tells an Admin with no datasources to add one here", () => {
      renderPane("ADMIN", [], ["create:datasources", "read:workspaces"]);

      expect(screen.getByTestId("t--datasource-managed-note").textContent).toBe(
        "As an Admin, you add datasources here with Bring your data, and change one with Edit on its page. Every app can use them.",
      );
      expect(
        screen.getByTestId("t--add-datasource-button-blank-screen"),
      ).toBeInTheDocument();
    });

    it("says nothing more to an Admin once there are datasources", () => {
      renderPane("ADMIN", [productsDS], ["create:datasources"]);

      expect(
        screen.queryByTestId("t--datasource-managed-note"),
      ).not.toBeInTheDocument();
    });

    it.each(["DEV", "APPROVER"])(
      "tells a %s that an Admin manages datasources, with no dead add button",
      (role) => {
        renderPane(role, [], ["read:workspaces"]);

        expect(
          screen.getByTestId("t--datasource-managed-note").textContent,
        ).toBe(
          "An Admin manages datasources. Ask an Admin to add or change one.",
        );
        expect(
          screen.queryByTestId("t--add-datasource-button-blank-screen"),
        ).not.toBeInTheDocument();
      },
    );

    it("keeps the note for a Dev who sees datasources but can't change them", () => {
      renderPane("DEV", [productsDS], ["read:workspaces"]);

      expect(screen.getByText("Products")).toBeInTheDocument();
      expect(
        screen.getByTestId("t--datasource-managed-note"),
      ).toBeInTheDocument();
    });
  });
});

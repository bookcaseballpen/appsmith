import {
  getHasCreateDatasourcePermission,
  getHasDeleteDatasourcePermission,
  getHasManageDatasourcePermission,
} from "./permissionPageHelpers";

// What the server sends a Dev for the default workspace and for a datasource an Admin made.
const DEV_WORKSPACE = [
  "read:workspaces",
  "manage:workspaceApplications",
  "read:workspaceDatasources",
  "execute:workspaceDatasources",
  "create:applications",
];
const DEV_DATASOURCE = [
  "create:datasourceActions",
  "execute:datasources",
  "read:datasources",
];

describe("fork datasource permission helpers", () => {
  it("hide create, edit and delete from a Dev", () => {
    expect(getHasCreateDatasourcePermission(false, DEV_WORKSPACE)).toBe(false);
    expect(getHasManageDatasourcePermission(false, DEV_DATASOURCE)).toBe(false);
    expect(getHasDeleteDatasourcePermission(false, DEV_DATASOURCE)).toBe(false);
  });

  it("allow them when the server grants them", () => {
    expect(
      getHasCreateDatasourcePermission(false, [
        ...DEV_WORKSPACE,
        "create:datasources",
      ]),
    ).toBe(true);
    expect(
      getHasManageDatasourcePermission(false, [
        ...DEV_DATASOURCE,
        "manage:datasources",
      ]),
    ).toBe(true);
    expect(
      getHasDeleteDatasourcePermission(false, [
        ...DEV_DATASOURCE,
        "delete:datasources",
      ]),
    ).toBe(true);
  });

  it("allow a datasource that has no permissions yet, as CE does", () => {
    expect(getHasManageDatasourcePermission(false, [])).toBe(true);
    expect(getHasCreateDatasourcePermission(false, undefined)).toBe(true);
  });
});

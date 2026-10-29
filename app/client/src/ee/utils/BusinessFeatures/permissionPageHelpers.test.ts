import {
  getHasCreateActionPermission,
  getHasCreateDatasourcePermission,
  getHasCreatePagePermission,
  getHasDeleteActionPermission,
  getHasDeleteDatasourcePermission,
  getHasDeletePagePermission,
  getHasExecuteActionPermission,
  getHasManageActionPermission,
  getHasManageDatasourcePermission,
  getHasManagePagePermission,
  hasCreateDSActionPermissionInApp,
} from "./permissionPageHelpers";
import { setEditorReadOnly } from "ee/utils/editorReadOnly";

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

describe("fork read-only editor permission helpers", () => {
  afterEach(() => setEditorReadOnly(false));

  const pageAndActionHelpers = [
    getHasCreatePagePermission,
    getHasManagePagePermission,
    getHasDeletePagePermission,
    getHasCreateActionPermission,
    getHasManageActionPermission,
    getHasDeleteActionPermission,
  ];

  it("deny creating, changing and deleting pages, queries and JS when read-only", () => {
    setEditorReadOnly(true);

    pageAndActionHelpers.forEach((helper) =>
      expect(helper(false, ["manage:pages", "manage:actions"])).toBe(false),
    );
    expect(
      hasCreateDSActionPermissionInApp({
        dsPermissions: DEV_DATASOURCE,
        isEnabled: false,
        pagePermissions: [],
      }),
    ).toBe(false);
  });

  it("still allow running queries when read-only", () => {
    setEditorReadOnly(true);

    expect(getHasExecuteActionPermission(false, [])).toBe(true);
  });

  it("allow everything as CE does when not read-only", () => {
    pageAndActionHelpers.forEach((helper) =>
      expect(helper(false, [])).toBe(true),
    );
    expect(
      hasCreateDSActionPermissionInApp({
        dsPermissions: DEV_DATASOURCE,
        isEnabled: false,
        pagePermissions: [],
      }),
    ).toBe(true);
  });
});

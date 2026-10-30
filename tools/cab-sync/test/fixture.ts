import type { JsonObject } from "../src/json.ts";

// An app as export_application returns it: two pages, nested widgets, a SQL query, an API query with a JSON body, a
// JS object with two functions, a deleted query, and the volatile fields and published side cab-sync leaves out.
export function sampleApp(): JsonObject {
  const dsl: JsonObject = {
    widgetName: "MainContainer",
    widgetId: "0",
    type: "CANVAS_WIDGET",
    version: 89,
    bottomRow: 380,
    children: [
      {
        widgetName: "OrdersTable",
        widgetId: "w1",
        parentId: "0",
        type: "TABLE_WIDGET_V2",
        tableData: "{{GetOrders.data}}",
        dynamicBindingPathList: [{ key: "tableData" }],
        topRow: 0,
        bottomRow: 28,
      },
      {
        widgetName: "Form1",
        widgetId: "w2",
        parentId: "0",
        type: "FORM_WIDGET",
        children: [
          {
            widgetName: "Canvas1",
            widgetId: "w3",
            parentId: "w2",
            type: "CANVAS_WIDGET",
            children: [
              {
                widgetName: "Submit",
                widgetId: "w4",
                parentId: "w3",
                type: "BUTTON_WIDGET",
                text: "Save",
                onClick: "{{Orders.save()}}",
              },
              {
                widgetName: "Title",
                widgetId: "w5",
                parentId: "w3",
                type: "TEXT_WIDGET",
                text: "Ünïcode & {{appsmith.user.name}}",
              },
            ],
          },
        ],
      },
    ],
  };

  return {
    clientSchemaVersion: 1,
    serverSchemaVersion: 11,
    artifactJsonType: "APPLICATION",
    exportedApplication: {
      name: "Orders Admin",
      slug: "orders-admin",
      isPublic: false,
      pages: [
        { id: "Orders", isDefault: true },
        { id: "Settings / Admin", isDefault: false },
      ],
      publishedPages: [{ id: "Orders", isDefault: true }],
      unpublishedApplicationDetail: { appPositioning: { type: "FIXED" } },
      publishedApplicationDetail: { appPositioning: { type: "FIXED" } },
      unpublishedAppLayout: { type: "DESKTOP" },
      publishedAppLayout: { type: "DESKTOP" },
      lastDeployedAt: "2026-10-01T10:00:00Z",
      modifiedBy: "dev@example.com",
      evaluationVersion: 2,
    },
    editModeTheme: { name: "Default-New", displayName: "Modern", isSystemTheme: true },
    publishedTheme: { name: "Default-New", displayName: "Modern", isSystemTheme: true },
    datasourceList: [
      { name: "Orders DB", pluginId: "postgres-plugin", gitSyncId: "ds1", datasourceConfiguration: { url: "db" } },
      { name: "Billing API", pluginId: "restapi-plugin", gitSyncId: "ds2" },
    ],
    decryptedFields: { "Orders DB": { password: "secret" } },
    customJSLibList: [],
    pageList: [
      {
        gitSyncId: "page-orders",
        unpublishedPage: {
          name: "Orders",
          slug: "orders",
          layouts: [{ viewMode: false, dsl, layoutOnLoadActions: [[{ name: "GetOrders" }]] }],
          userPermissions: ["read:pages"],
        },
        publishedPage: { name: "Orders", slug: "orders", layouts: [{ dsl: { widgetName: "MainContainer" } }] },
        policies: [],
      },
      {
        gitSyncId: "page-settings",
        unpublishedPage: {
          name: "Settings / Admin",
          slug: "settings-admin",
          isHidden: true,
          layouts: [{ viewMode: false, dsl: { widgetName: "MainContainer", widgetId: "0", children: [] } }],
        },
      },
      {
        gitSyncId: "page-gone",
        unpublishedPage: { name: "Gone", deletedAt: "2026-10-02T10:00:00Z", layouts: [] },
        publishedPage: { name: "Gone", layouts: [] },
      },
    ],
    actionList: [
      {
        id: "Orders_GetOrders",
        gitSyncId: "q1",
        pluginType: "DB",
        pluginId: "postgres-plugin",
        unpublishedAction: {
          name: "GetOrders",
          pageId: "Orders",
          datasource: { id: "Orders DB", name: "Orders DB", pluginId: "postgres-plugin" },
          actionConfiguration: { body: "SELECT *\nFROM orders\nWHERE id > {{0}};\n", timeoutInMillisecond: 10000 },
          executeOnLoad: true,
          userPermissions: ["execute:actions"],
          createdAt: "2026-09-01T10:00:00Z",
        },
        publishedAction: { name: "GetOrders", actionConfiguration: { body: "SELECT 1" } },
      },
      {
        id: "Settings / Admin_SaveSettings",
        gitSyncId: "q2",
        pluginType: "API",
        pluginId: "restapi-plugin",
        unpublishedAction: {
          name: "SaveSettings",
          pageId: "Settings / Admin",
          datasource: { id: "Billing API", name: "Billing API" },
          actionConfiguration: { httpMethod: "POST", path: "/settings", body: '{\n  "a": 1\n}' },
        },
      },
      {
        id: "Orders_Deleted",
        gitSyncId: "q3",
        pluginType: "DB",
        pluginId: "postgres-plugin",
        unpublishedAction: { name: "Deleted", pageId: "Orders", deletedAt: "2026-10-02T10:00:00Z" },
      },
      {
        id: "Orders_Orders.save",
        gitSyncId: "f1",
        pluginType: "JS",
        pluginId: "js-plugin",
        unpublishedAction: {
          name: "save",
          fullyQualifiedName: "Orders.save",
          pageId: "Orders",
          collectionId: "Orders_Orders",
          actionConfiguration: { body: "async () => { await GetOrders.run(); }", isAsync: true },
          jsonPathKeys: ["async () => { await GetOrders.run(); }"],
          executeOnLoad: false,
        },
      },
      {
        id: "Orders_Orders.load",
        gitSyncId: "f2",
        pluginType: "JS",
        pluginId: "js-plugin",
        unpublishedAction: {
          name: "load",
          fullyQualifiedName: "Orders.load",
          pageId: "Orders",
          collectionId: "Orders_Orders",
          actionConfiguration: { body: "() => 1" },
          executeOnLoad: true,
        },
      },
    ],
    actionCollectionList: [
      {
        id: "Orders_Orders",
        gitSyncId: "js1",
        unpublishedCollection: {
          name: "Orders",
          pageId: "Orders",
          pluginType: "JS",
          pluginId: "js-plugin",
          body: "export default {\n\tsave: async () => { await GetOrders.run(); },\n\tload: () => 1,\n}\n",
        },
        publishedCollection: { name: "Orders", body: "export default {}" },
      },
    ],
  };
}

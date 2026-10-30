import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { isObject, type Json, type JsonObject } from "../src/json.ts";
import { assembleApp, diffFiles, fileName, LayoutError, splitApp } from "../src/layout.ts";
import { sampleApp } from "./fixture.ts";

function reversedKeys(value: Json): Json {
  if (Array.isArray(value)) return value.map(reversedKeys);

  if (isObject(value)) {
    return Object.fromEntries(
      Object.keys(value)
        .reverse()
        .map((key) => [key, reversedKeys(value[key])]),
    );
  }

  return value;
}

function sorted(files: Map<string, string>): [string, string][] {
  return [...files].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}

describe("splitApp", () => {
  it("writes the documented layout", () => {
    const paths = [...splitApp(sampleApp()).keys()].sort();

    assert.deepEqual(paths, [
      "application.json",
      "datasources.json",
      "pages/Orders/jsobjects/Orders.js",
      "pages/Orders/jsobjects/Orders.jsobject.json",
      "pages/Orders/page.json",
      "pages/Orders/queries/GetOrders.query.json",
      "pages/Orders/queries/GetOrders.sql",
      "pages/Orders/widgets/Canvas1.json",
      "pages/Orders/widgets/Form1.json",
      "pages/Orders/widgets/MainContainer.json",
      "pages/Orders/widgets/OrdersTable.json",
      "pages/Orders/widgets/Submit.json",
      "pages/Orders/widgets/Title.json",
      "pages/Settings %2F Admin/page.json",
      "pages/Settings %2F Admin/queries/SaveSettings.json",
      "pages/Settings %2F Admin/queries/SaveSettings.query.json",
      "pages/Settings %2F Admin/widgets/MainContainer.json",
      "theme.json",
    ]);
  });

  it("gives the same bytes whatever the key order of the export", () => {
    const app = sampleApp();

    assert.deepEqual(sorted(splitApp(reversedKeys(app) as JsonObject)), sorted(splitApp(app)));
  });

  it("keeps bodies byte for byte and JSON sorted with a final newline", () => {
    const files = splitApp(sampleApp());

    assert.equal(files.get("pages/Orders/queries/GetOrders.sql"), "SELECT *\nFROM orders\nWHERE id > {{0}};\n");
    assert.equal(files.get("pages/Settings %2F Admin/queries/SaveSettings.json"), '{\n  "a": 1\n}');
    assert.equal(
      files.get("pages/Orders/jsobjects/Orders.js"),
      "export default {\n\tsave: async () => { await GetOrders.run(); },\n\tload: () => 1,\n}\n",
    );
    assert.equal(files.get("datasources.json"), `[
  {
    "name": "Billing API",
    "pluginId": "restapi-plugin"
  },
  {
    "name": "Orders DB",
    "pluginId": "postgres-plugin"
  }
]
`);
  });

  it("leaves out the published side, deleted entities, secrets and volatile fields", () => {
    const all = [...splitApp(sampleApp()).values()].join("\n");

    for (const key of [
      "publishedPage",
      "publishedAction",
      "publishedCollection",
      "publishedTheme",
      "publishedPages",
      "publishedAppLayout",
      "publishedApplicationDetail",
      "lastDeployedAt",
      "decryptedFields",
      "datasourceConfiguration",
      "userPermissions",
      "createdAt",
      "modifiedBy",
      "jsonPathKeys",
    ]) {
      assert.ok(!all.includes(`"${key}"`), `${key} must not be written`);
    }

    for (const value of ["secret", '"Deleted"', '"Gone"']) {
      assert.ok(!all.includes(value), `${value} must not be written`);
    }
  });

  it("lists child widgets by name, in order", () => {
    const form = JSON.parse(splitApp(sampleApp()).get("pages/Orders/widgets/Canvas1.json") ?? "");

    assert.deepEqual(form.children, ["Submit", "Title"]);
  });

  it("refuses two widgets with the same name", () => {
    const app = sampleApp();
    const page = (app.pageList as JsonObject[])[0];
    const dsl = ((page.unpublishedPage as JsonObject).layouts as JsonObject[])[0].dsl as JsonObject;

    (dsl.children as JsonObject[])[1].widgetName = "OrdersTable";

    assert.throws(() => splitApp(app), LayoutError);
  });

  it("refuses names that differ only in letter case", () => {
    const app = sampleApp();
    const page = (app.pageList as JsonObject[])[0];
    const dsl = ((page.unpublishedPage as JsonObject).layouts as JsonObject[])[0].dsl as JsonObject;

    (dsl.children as JsonObject[])[1].widgetName = "orderstable";

    assert.throws(() => splitApp(app), /clashes/);
  });
});

describe("assembleApp", () => {
  it("gives back the edit copy the import needs", () => {
    const app = assembleApp(splitApp(sampleApp()));
    const orders = (app.pageList as JsonObject[]).find(
      (page) => (page.unpublishedPage as JsonObject).name === "Orders",
    ) as JsonObject;
    const dsl = ((orders.unpublishedPage as JsonObject).layouts as JsonObject[])[0].dsl as JsonObject;
    const expectedDsl = (
      ((sampleApp().pageList as JsonObject[])[0].unpublishedPage as JsonObject).layouts as JsonObject[]
    )[0].dsl;

    assert.deepEqual(dsl, expectedDsl);
    assert.equal((app.pageList as JsonObject[]).length, 2);
    assert.deepEqual(
      (app.actionList as JsonObject[]).map((action) => (action.unpublishedAction as JsonObject).name).sort(),
      ["GetOrders", "SaveSettings", "load", "save"],
    );

    const query = (app.actionList as JsonObject[]).find(
      (action) => (action.unpublishedAction as JsonObject).name === "GetOrders",
    ) as JsonObject;

    assert.deepEqual((query.unpublishedAction as JsonObject).actionConfiguration, {
      body: "SELECT *\nFROM orders\nWHERE id > {{0}};\n",
      timeoutInMillisecond: 10000,
    });
    assert.equal(
      ((app.actionCollectionList as JsonObject[])[0].unpublishedCollection as JsonObject).body,
      "export default {\n\tsave: async () => { await GetOrders.run(); },\n\tload: () => 1,\n}\n",
    );
    assert.equal((app.exportedApplication as JsonObject).name, "Orders Admin");
  });

  it("round-trips: split, assemble, split gives byte-identical files", () => {
    const first = splitApp(sampleApp());
    const second = splitApp(assembleApp(first));

    assert.deepEqual(sorted(second), sorted(first));
  });

  it("keeps the app's tags in application.json and gives them back", () => {
    const app = sampleApp();

    (app.exportedApplication as JsonObject).tags = ["sales", "finance"];

    const files = splitApp(app);
    const application = JSON.parse(files.get("application.json") as string);

    assert.deepEqual(application.exportedApplication.tags, ["sales", "finance"]);
    assert.deepEqual((assembleApp(files).exportedApplication as JsonObject).tags, ["sales", "finance"]);
    assert.deepEqual(sorted(splitApp(assembleApp(files))), sorted(files));
  });

  it("writes no tags for an app with an empty list, the same as for one without", () => {
    const tagged = sampleApp();

    (tagged.exportedApplication as JsonObject).tags = [];

    assert.deepEqual(sorted(splitApp(tagged)), sorted(splitApp(sampleApp())));
  });

  it("sends an empty tag list when application.json has no tags, so removing them clears them", () => {
    const files = splitApp(sampleApp());

    assert.equal(JSON.parse(files.get("application.json") as string).exportedApplication.tags, undefined);
    assert.deepEqual((assembleApp(files).exportedApplication as JsonObject).tags, []);
  });

  it("puts a query on the page whose folder holds it", () => {
    const files = splitApp(sampleApp());
    const meta = files.get("pages/Orders/queries/GetOrders.query.json") ?? "";

    files.delete("pages/Orders/queries/GetOrders.query.json");
    files.set("pages/Settings %2F Admin/queries/GetOrders.query.json", meta);
    files.set(
      "pages/Settings %2F Admin/queries/GetOrders.sql",
      files.get("pages/Orders/queries/GetOrders.sql") ?? "",
    );
    files.delete("pages/Orders/queries/GetOrders.sql");

    const query = (assembleApp(files).actionList as JsonObject[]).find(
      (action) => (action.unpublishedAction as JsonObject).name === "GetOrders",
    ) as JsonObject;

    assert.equal((query.unpublishedAction as JsonObject).pageId, "Settings / Admin");
  });

  it("refuses a file that is not part of the layout", () => {
    const files = splitApp(sampleApp());

    files.set("pages/Orders/queries/GetOrders.sq", "SELECT 1");

    assert.throws(() => assembleApp(files), /not part of the app layout/);
  });

  it("refuses two body files for one query", () => {
    const files = splitApp(sampleApp());

    files.set("pages/Orders/queries/GetOrders.txt", "SELECT 1");

    assert.throws(() => assembleApp(files), /more than one body file/);
  });

  it("refuses a widget no parent lists", () => {
    const files = splitApp(sampleApp());

    files.set(
      "pages/Orders/widgets/Stray.json",
      JSON.stringify({ widgetName: "Stray", widgetId: "w9", type: "TEXT_WIDGET" }),
    );

    assert.throws(() => assembleApp(files), /exactly one top widget/);
  });

  it("refuses a child that has no file", () => {
    const files = splitApp(sampleApp());

    files.delete("pages/Orders/widgets/Title.json");

    assert.throws(() => assembleApp(files), /Title, which has no file/);
  });

  it("ignores dot files", () => {
    const files = splitApp(sampleApp());

    files.set("pages/.DS_Store", "x");

    assert.doesNotThrow(() => assembleApp(files));
  });
});

describe("fileName", () => {
  it("keeps plain names and encodes the rest", () => {
    assert.equal(fileName("GetOrders"), "GetOrders");
    assert.equal(fileName("Settings / Admin"), "Settings %2F Admin");
    assert.equal(fileName("50%"), "50%25");
    assert.equal(fileName("trailing."), "trailing%2E");
    assert.equal(fileName(".hidden"), "%2Ehidden");
    assert.equal(fileName("CON"), "%43ON");
    assert.equal(fileName("Ünïcode"), "%C3%9Cn%C3%AFcode");
  });
});

describe("diffFiles", () => {
  it("lists changed, repo-only and instance-only files", () => {
    const repo = new Map([
      ["a", "1"],
      ["b", "2"],
      ["c", "3"],
    ]);
    const instance = new Map([
      ["a", "1"],
      ["b", "changed"],
      ["d", "4"],
    ]);

    assert.deepEqual(diffFiles(repo, instance), [
      { path: "b", change: "modified" },
      { path: "c", change: "only-in-repo" },
      { path: "d", change: "only-in-instance" },
    ]);
  });
});

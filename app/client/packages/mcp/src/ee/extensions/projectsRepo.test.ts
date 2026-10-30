import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { buildMcpServer, createAppsmithApi } from "../../app.js";
import { TOOL_CATALOG } from "../../builder/capabilities.js";
import { createExtensionApi } from "./api.js";
import {
  MAX_IMPORT_ARTIFACT_BYTES,
  withoutDatasourceDetails,
} from "./tools.js";

// Fork: the projects-repo tools export_application and import_application (ee/extensions/{catalog,api,tools}.ts).

const API_BASE_URL = "http://server.test";

const EXPORT = {
  artifactJsonType: "APPLICATION",
  exportedApplication: { name: "Orders" },
  datasourceList: [
    {
      name: "Orders DB",
      pluginId: "postgres-plugin",
      datasourceConfiguration: { url: "db.internal" },
    },
  ],
  decryptedFields: { "Orders DB": { password: "secret" } },
  pageList: [{ unpublishedPage: { name: "Page1" } }],
  actionList: [
    {
      unpublishedAction: {
        name: "GetOrders",
        datasource: {
          id: "Orders DB",
          name: "Orders DB",
          datasourceConfiguration: { url: "db.internal" },
        },
      },
    },
    {
      unpublishedAction: {
        name: "CallApi",
        datasource: {
          name: "DEFAULT_REST_DATASOURCE",
          datasourceConfiguration: {
            url: "https://api.example.com",
            authentication: { password: "secret" },
          },
        },
      },
    },
  ],
};

const IMPORTED = {
  applicationId: "app1",
  workspaceId: "ws1",
  name: "Orders",
  unconfiguredDatasources: [],
  unchanged: false,
  tagsOnly: false,
};

interface RecordedCall {
  method: string;
  url: string;
  body: unknown;
}

function recordingFetch(
  calls: RecordedCall[],
  respond: (url: string) => Response = (url) =>
    new Response(
      JSON.stringify({ data: url.includes("/import/") ? IMPORTED : EXPORT }),
      { status: 200 },
    ),
): typeof fetch {
  return (async (url: string, init?: RequestInit) => {
    calls.push({
      method: (init?.method ?? "GET").toUpperCase(),
      url: String(url),
      body: init?.body,
    });

    return respond(String(url));
  }) as unknown as typeof fetch;
}

async function connect(fetchFn: typeof fetch): Promise<Client> {
  const server = buildMcpServer(
    createAppsmithApi("token", API_BASE_URL, fetchFn),
    { dataEnabled: false, jsEnabled: false, actorId: "dev@appsmith.com" },
  );
  const client = new Client({ name: "projects-repo-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();

  await Promise.all([
    server.connect(serverTransport),
    client.connect(clientTransport),
  ]);

  return client;
}

async function callTool(
  client: Client,
  name: string,
  args: Record<string, unknown>,
) {
  const res = await client.callTool({ name, arguments: args });
  const text = (res.content as { text: string }[])[0].text;

  return { isError: res.isError === true, text };
}

describe("projects-repo MCP tools", () => {
  const clients: Client[] = [];

  afterEach(async () => {
    for (const client of clients.splice(0)) await client.close();
  });

  async function session(fetchFn: typeof fetch): Promise<Client> {
    const client = await connect(fetchFn);

    clients.push(client);

    return client;
  }

  it("registers both tools, always on, export read-only and import a non-destructive write", async () => {
    const client = await session(recordingFetch([]));
    const { tools } = await client.listTools();
    const byName = new Map(tools.map((tool) => [tool.name, tool]));

    expect(byName.get("export_application")?.annotations).toEqual({
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    });
    expect(byName.get("import_application")?.annotations).toEqual({
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: false,
      openWorldHint: false,
    });
    expect(byName.get("import_application")?.inputSchema.required).toEqual([
      "workspaceId",
      "artifact",
    ]);
    expect(byName.get("import_application")?.description).toContain(
      "EDIT COPY only",
    );
    expect(
      TOOL_CATALOG.filter((tool) => tool.name.endsWith("_application")).map(
        (tool) => [tool.name, tool.gate],
      ),
    ).toEqual(
      expect.arrayContaining([
        ["export_application", "always"],
        ["import_application", "always"],
      ]),
    );
  });

  it("export_application GETs the edit copy and passes no datasource details on", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "export_application", {
      applicationId: "app1",
    });
    const exported = JSON.parse(res.text);

    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ["GET", `${API_BASE_URL}/api/v1/projects-repo/applications/app1`],
    ]);
    expect(exported.datasourceList).toEqual([
      { name: "Orders DB", pluginId: "postgres-plugin" },
    ]);
    expect(exported.decryptedFields).toBeUndefined();
    expect(exported.actionList[0].unpublishedAction.datasource).toEqual({
      id: "Orders DB",
      name: "Orders DB",
    });
    expect(
      exported.actionList[1].unpublishedAction.datasource
        .datasourceConfiguration,
    ).toEqual({ url: "https://api.example.com" });
    expect(res.text).not.toContain("secret");
    expect(res.text).not.toContain("db.internal");
  });

  it("import_application POSTs the app JSON over the app's edit copy", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const artifact = { artifactJsonType: "APPLICATION", pageList: [] };
    const res = await callTool(client, "import_application", {
      workspaceId: "ws1",
      applicationId: "app1",
      artifact,
    });

    expect(res.isError).toBe(false);
    expect(JSON.parse(res.text)).toEqual(IMPORTED);
    expect(calls).toEqual([
      {
        method: "POST",
        url: `${API_BASE_URL}/api/v1/projects-repo/applications/import/ws1?applicationId=app1`,
        body: JSON.stringify(artifact),
      },
    ]);
  });

  it("import_application without applicationId creates a new app", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));

    await callTool(client, "import_application", {
      workspaceId: "ws1",
      artifact: { artifactJsonType: "APPLICATION" },
    });

    expect(calls.map((call) => call.url)).toEqual([
      `${API_BASE_URL}/api/v1/projects-repo/applications/import/ws1`,
    ]);
  });

  it("refuses an artifact over the size limit, without calling the server", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "import_application", {
      workspaceId: "ws1",
      artifact: { padding: "x".repeat(MAX_IMPORT_ARTIFACT_BYTES) },
    });

    expect(res.isError).toBe(true);
    expect(calls).toEqual([]);
  });

  it.each([
    [400, "import_application", "not a valid app export"],
    [403, "import_application", "Developer or Administrator"],
    [404, "export_application", "No application with this id"],
  ])("maps a %i from %s to guidance", async (status, tool, expected) => {
    const client = await session(
      recordingFetch(
        [],
        () =>
          new Response(
            JSON.stringify({
              responseMeta: { status, success: false, error: { code: "AE" } },
            }),
            { status },
          ),
      ),
    );
    const body = JSON.parse(
      (
        await callTool(client, tool, {
          applicationId: "app1",
          workspaceId: "ws1",
          artifact: { artifactJsonType: "APPLICATION" },
        })
      ).text,
    );

    expect(body.status).toBe(status);
    expect(body.error).toContain(expected);
  });

  it("the API methods go through the request they are handed", async () => {
    const request = jest.fn(async () => IMPORTED);
    const api = createExtensionApi(request as never);

    await api.exportApplicationEditCopy("a b");
    await api.importApplicationEditCopy("ws1", undefined, { a: 1 });
    expect(request.mock.calls).toEqual([
      [
        "/api/v1/projects-repo/applications/a%20b",
        undefined,
        { extractErrorCode: true, timeoutMs: 120000 },
      ],
      [
        "/api/v1/projects-repo/applications/import/ws1",
        { method: "POST", body: '{"a":1}' },
        { extractErrorCode: true, timeoutMs: 120000 },
      ],
    ]);
  });

  it("withoutDatasourceDetails drops the published and secret top-level parts", () => {
    expect(
      Object.keys(
        withoutDatasourceDetails({
          publishedTheme: {},
          modifiedResources: {},
          datasourceConfigurationStructureList: [],
          pageList: [],
        }),
      ),
    ).toEqual(["pageList"]);
  });
});

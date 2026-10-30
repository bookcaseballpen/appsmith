import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import {
  buildMcpServer,
  createAppsmithApi,
  filterApplicationsByTags,
} from "../../app.js";
import { TOOL_CATALOG } from "../../builder/capabilities.js";
import { createExtensionApi } from "./api.js";

// Fork: app tags over MCP: set_app_tags (ee/extensions/{catalog,api,tools}.ts), and the tags in list_applications and
// get_application_context (app.ts).

const API_BASE_URL = "http://server.test";

const APPLICATIONS = [
  { id: "a1", name: "Orders", tags: ["sales", "finance"] },
  { id: "a2", name: "Leave", tags: ["hr"] },
  { id: "a3", name: "Budget", tags: ["finance"] },
  { id: "a4", name: "Untagged" },
];

interface RecordedCall {
  method: string;
  url: string;
  body: unknown;
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data }), { status });
}

function recordingFetch(
  calls: RecordedCall[],
  respond: (url: string) => Response = (url) => {
    if (url.includes("/api/v1/app-tags/")) {
      return json({ applicationId: "a1", tags: ["sales"] });
    }

    if (url.includes("/api/v1/applications/home")) return json(APPLICATIONS);

    if (url.includes("/api/v1/pages?applicationId=")) {
      return json({ application: { id: "a1", tags: ["sales", "q3"] } });
    }

    return json({});
  },
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
  const client = new Client({ name: "app-tags-test", version: "1.0.0" });
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

describe("app tags over MCP", () => {
  const clients: Client[] = [];

  afterEach(async () => {
    for (const client of clients.splice(0)) await client.close();
  });

  async function session(fetchFn: typeof fetch): Promise<Client> {
    const client = await connect(fetchFn);

    clients.push(client);

    return client;
  }

  it("registers set_app_tags, always on, as an idempotent non-destructive write", async () => {
    const client = await session(recordingFetch([]));
    const { tools } = await client.listTools();
    const tool = tools.find((entry) => entry.name === "set_app_tags");

    expect(tool?.annotations).toEqual({
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    });
    expect(tool?.inputSchema.required).toEqual(["applicationId", "tags"]);
    expect(tool?.description).toContain("does not change the edit copy");
    expect(
      TOOL_CATALOG.find((entry) => entry.name === "set_app_tags")?.gate,
    ).toBe("always");
  });

  it("set_app_tags PUTs the full list to the app-tags route", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "set_app_tags", {
      applicationId: "a1",
      tags: ["Sales", " sales "],
    });

    expect(res.isError).toBe(false);
    expect(JSON.parse(res.text)).toEqual({
      applicationId: "a1",
      tags: ["sales"],
    });
    expect(calls).toEqual([
      {
        method: "PUT",
        url: `${API_BASE_URL}/api/v1/app-tags/applications/a1`,
        body: JSON.stringify({ tags: ["Sales", " sales "] }),
      },
    ]);
  });

  it("refuses an oversized list without calling the server", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "set_app_tags", {
      applicationId: "a1",
      tags: Array.from({ length: 21 }, (_, index) => `tag${index}`),
    });

    expect(res.isError).toBe(true);
    expect(calls).toEqual([]);
  });

  it.each([
    [400, "at most 10 different tags"],
    [403, "Developer role with edit access"],
    [404, "No application with this id"],
  ])("maps a %i to guidance", async (status, expected) => {
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
        await callTool(client, "set_app_tags", {
          applicationId: "a1",
          tags: [],
        })
      ).text,
    );

    expect(body.code).toBe("set_tags_refused");
    expect(body.status).toBe(status);
    expect(body.error).toContain(expected);
  });

  it("the API method goes through the request it is handed", async () => {
    const request = jest.fn(async () => ({ applicationId: "a b", tags: [] }));
    const api = createExtensionApi(request as never);

    await api.setApplicationTags("a b", ["x"]);
    expect(request.mock.calls).toEqual([
      [
        "/api/v1/app-tags/applications/a%20b",
        { method: "PUT", body: '{"tags":["x"]}' },
        { extractErrorCode: true },
      ],
    ]);
  });

  it.each([
    [undefined, undefined, ["a1", "a2", "a3", "a4"]],
    [["Finance"], undefined, ["a1", "a3"]],
    [["finance", "hr"], "any", ["a1", "a2", "a3"]],
    [["finance", " SALES "], "all", ["a1"]],
  ])(
    "list_applications with tags %j (%s) lists %j",
    async (tags, tagMode, expected) => {
      const client = await session(recordingFetch([]));
      const res = await callTool(client, "list_applications", {
        workspaceId: "ws1",
        ...(tags !== undefined ? { tags } : {}),
        ...(tagMode !== undefined ? { tagMode } : {}),
      });

      expect(
        (JSON.parse(res.text) as { id: string }[]).map((app) => app.id),
      ).toEqual(expected);
    },
  );

  it("get_application_context reports the app's tags", async () => {
    const client = await session(recordingFetch([]));
    const res = await callTool(client, "get_application_context", {
      applicationId: "a1",
      pageId: "p1",
      layoutId: "l1",
    });

    expect(JSON.parse(res.text).tags).toEqual(["sales", "q3"]);
  });

  it("filterApplicationsByTags leaves a non-list response as it is", () => {
    const response = { unexpected: true };

    expect(filterApplicationsByTags(response, ["x"], "any")).toBe(response);
  });
});

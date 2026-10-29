import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { buildMcpServer, createAppsmithApi } from "../../app.js";
import { TOOL_CATALOG } from "../../builder/capabilities.js";
import { SERVER_INSTRUCTIONS } from "../../builder/instructions.js";
import { createExtensionApi } from "./api.js";

// Fork: the release-approval tools (ee/extensions/{catalog,api,tools,routeContract}.ts).

const API_BASE_URL = "http://server.test";

const STATE = {
  applicationId: "app1",
  applicationName: "Orders",
  workspaceId: "ws1",
  defaultBasePageId: "page1",
  status: "SUBMITTED",
  version: "0123456789ab",
  submittedBy: "dev@appsmith.com",
  submittedAt: 1793268000.125,
  reviewedBy: null,
  reviewedAt: null,
  comment: null,
  history: [
    {
      type: "SUBMITTED",
      by: "dev@appsmith.com",
      at: 1793268000.125,
      comment: null,
    },
  ],
};

interface RecordedCall {
  method: string;
  url: string;
  auth: string | null;
  body: unknown;
}

function recordingFetch(
  calls: RecordedCall[],
  respond: () => Response = () =>
    new Response(JSON.stringify({ data: STATE }), { status: 200 }),
): typeof fetch {
  return (async (url: string, init?: RequestInit) => {
    calls.push({
      method: (init?.method ?? "GET").toUpperCase(),
      url: String(url),
      auth: new Headers(init?.headers).get("Authorization"),
      body: init?.body,
    });

    return respond();
  }) as unknown as typeof fetch;
}

function errorResponse(status: number, message: string): Response {
  return new Response(
    JSON.stringify({
      responseMeta: {
        status,
        success: false,
        error: { code: "AE-APP-4000", message },
      },
    }),
    { status },
  );
}

async function connect(fetchFn: typeof fetch): Promise<Client> {
  const server = buildMcpServer(
    createAppsmithApi("token", API_BASE_URL, fetchFn),
    { dataEnabled: false, jsEnabled: false, actorId: "dev@appsmith.com" },
  );
  const client = new Client({ name: "approvals-test", version: "1.0.0" });
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

describe("release-approval MCP tools", () => {
  const clients: Client[] = [];

  afterEach(async () => {
    for (const client of clients.splice(0)) await client.close();
  });

  async function session(fetchFn: typeof fetch): Promise<Client> {
    const client = await connect(fetchFn);

    clients.push(client);

    return client;
  }

  it("registers both tools, always on, with their annotations", async () => {
    const client = await session(recordingFetch([]));
    const { tools } = await client.listTools();
    const byName = new Map(tools.map((tool) => [tool.name, tool]));

    expect(byName.get("submit_for_approval")?.annotations).toEqual({
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: false,
      openWorldHint: false,
    });
    expect(byName.get("get_approval_status")?.annotations).toEqual({
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    });
    expect(byName.get("submit_for_approval")?.inputSchema.required).toEqual([
      "applicationId",
    ]);
    expect(
      TOOL_CATALOG.filter((tool) => tool.name.includes("approval")).map(
        (tool) => [tool.name, tool.gate],
      ),
    ).toEqual([
      ["submit_for_approval", "always"],
      ["get_approval_status", "always"],
    ]);
  });

  it("tells the agent when to submit and that approving is for people", async () => {
    const client = await session(recordingFetch([]));
    const { tools } = await client.listTools();
    const submit = tools.find((tool) => tool.name === "submit_for_approval");

    expect(submit?.description).toContain("finished UAT");
    expect(submit?.description).toContain("CURRENT edit-mode version");
    expect(submit?.description).toContain("cancels the request");
    expect(submit?.description).toContain("not available via MCP");
    expect(SERVER_INSTRUCTIONS).toContain("submit_for_approval");
  });

  it("has no tool that approves or rejects", async () => {
    const client = await session(recordingFetch([]));
    const names = (await client.listTools()).tools.map((tool) => tool.name);

    expect(names.filter((name) => /approve|reject/i.test(name))).toEqual([]);
    expect(
      TOOL_CATALOG.filter((tool) => /approve|reject/i.test(tool.name)),
    ).toEqual([]);
  });

  it("submit_for_approval POSTs to the submit route and returns the state", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "submit_for_approval", {
      applicationId: "app1",
    });

    expect(res.isError).toBe(false);
    expect(JSON.parse(res.text)).toEqual(STATE);
    expect(calls).toEqual([
      {
        method: "POST",
        url: `${API_BASE_URL}/api/v1/approvals/applications/app1/submit`,
        auth: "Bearer token",
        body: undefined,
      },
    ]);
  });

  it("submit_for_approval sends the note for the Approver when one is given", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "submit_for_approval", {
      applicationId: "app1",
      note: "  New order filter ",
    });

    expect(res.isError).toBe(false);
    expect(calls.map((call) => [call.method, call.url, call.body])).toEqual([
      [
        "POST",
        `${API_BASE_URL}/api/v1/approvals/applications/app1/submit`,
        JSON.stringify({ note: "New order filter" }),
      ],
    ]);
  });

  it("refuses a note longer than the server keeps, without calling the server", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "submit_for_approval", {
      applicationId: "app1",
      note: "x".repeat(501),
    });

    expect(res.isError).toBe(true);
    expect(calls).toEqual([]);
  });

  it("get_approval_status GETs the application's state", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "get_approval_status", {
      applicationId: "app1",
    });

    expect(JSON.parse(res.text)).toEqual(STATE);
    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ["GET", `${API_BASE_URL}/api/v1/approvals/applications/app1`],
    ]);
  });

  it("refuses an applicationId that is not an id, without calling the server", async () => {
    const calls: RecordedCall[] = [];
    const client = await session(recordingFetch(calls));
    const res = await callTool(client, "submit_for_approval", {
      applicationId: "../approve",
    });

    expect(res.isError).toBe(true);
    expect(calls).toEqual([]);
  });

  it("explains a refused submission with the status and Appsmith error code", async () => {
    const client = await session(
      recordingFetch([], () =>
        errorResponse(400, "This version is already waiting for approval."),
      ),
    );
    const res = await callTool(client, "submit_for_approval", {
      applicationId: "app1",
    });
    const body = JSON.parse(res.text);

    expect(body).toMatchObject({
      code: "approval_submit_refused",
      status: 400,
      errorCode: "AE-APP-4000",
    });
    expect(body.error).toContain("already waiting for approval");
    expect(body.error).toContain("get_approval_status");
  });

  it.each([
    [403, "submit_for_approval", "Developer or Administrator"],
    [404, "get_approval_status", "No application with this id"],
  ])("maps a %i from %s to guidance", async (status, tool, expected) => {
    const client = await session(
      recordingFetch([], () => errorResponse(status, "nope")),
    );
    const body = JSON.parse(
      (await callTool(client, tool, { applicationId: "app1" })).text,
    );

    expect(body.status).toBe(status);
    expect(body.error).toContain(expected);
  });

  it("fails like a core tool on an unexpected server error", async () => {
    const client = await session(
      recordingFetch([], () => errorResponse(500, "boom")),
    );
    const res = await callTool(client, "get_approval_status", {
      applicationId: "app1",
    });

    expect(res.isError).toBe(true);
    expect(res.text).toContain("Appsmith API request failed (500)");
  });

  it("the API methods go through the request they are handed", async () => {
    const request = jest.fn(async () => STATE);
    const api = createExtensionApi(request as never);

    await expect(api.submitForApproval("a b")).resolves.toEqual(STATE);
    await expect(api.getApprovalStatus("app1")).resolves.toEqual(STATE);
    expect(request.mock.calls).toEqual([
      [
        "/api/v1/approvals/applications/a%20b/submit",
        { method: "POST" },
        { extractErrorCode: true },
      ],
      [
        "/api/v1/approvals/applications/app1",
        undefined,
        { extractErrorCode: true },
      ],
    ]);
  });
});

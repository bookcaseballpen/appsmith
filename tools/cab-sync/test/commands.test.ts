import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server } from "node:http";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import type { AddressInfo } from "node:net";
import { join } from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { readTree } from "../src/files.ts";
import { clone, type JsonObject } from "../src/json.ts";
import { parseArgs, run, slugify } from "../src/commands.ts";
import { parseEventStream } from "../src/mcp.ts";
import { sampleApp } from "./fixture.ts";

// The commands against a fake Appsmith MCP server over HTTP: it answers in server-sent events like the real one, keeps
// one app, and on import replaces only the edit side, as the instance does.

const TOKEN = "mcp_test-token";
const TMP = join(import.meta.dirname, "..", ".test-tmp");

interface FakeState {
  app: JsonObject;
  imports: { applicationId: string | undefined; artifact: JsonObject }[];
  submits: JsonObject[];
  headers: IncomingMessage["headers"][];
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let body = "";

    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => resolve(body));
  });
}

// What the instance stores after an import over the app: the file's edit side, the stored published side, and the
// permission fields it adds to everything it returns.
function importOver(stored: JsonObject, artifact: JsonObject): JsonObject {
  const result = clone(artifact);
  const published = new Map(
    (stored.actionList as JsonObject[]).map((action) => [action.gitSyncId, action.publishedAction]),
  );

  for (const action of result.actionList as JsonObject[]) {
    if (published.get(action.gitSyncId) !== undefined) action.publishedAction = published.get(action.gitSyncId)!;

    (action.unpublishedAction as JsonObject).userPermissions = ["execute:actions"];
  }

  (result.exportedApplication as JsonObject).publishedPages = (
    stored.exportedApplication as JsonObject
  ).publishedPages;

  return result;
}

function fakeServer(state: FakeState): Server {
  return createServer(async (req, res) => {
    state.headers.push(req.headers);

    if (req.headers.authorization !== `Bearer ${TOKEN}`) {
      res.writeHead(401).end();

      return;
    }

    if (req.method === "DELETE") {
      res.writeHead(200).end();

      return;
    }

    const message = JSON.parse(await readBody(req));

    if (message.id === undefined) {
      res.writeHead(202).end();

      return;
    }

    const answer = (result: unknown) => {
      res.writeHead(200, { "Content-Type": "text/event-stream", "Mcp-Session-Id": "session-1" });
      // A notification before the answer, as a real server may send.
      res.write(`event: message\ndata: ${JSON.stringify({ jsonrpc: "2.0", method: "notifications/message", params: {} })}\n\n`);
      res.end(`event: message\ndata: ${JSON.stringify({ jsonrpc: "2.0", id: message.id, result })}\n\n`);
    };
    const tool = (data: unknown) => answer({ content: [{ type: "text", text: JSON.stringify(data) }] });

    if (message.method === "initialize") {
      answer({ protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "fake" } });

      return;
    }

    const { name, arguments: args } = message.params;
    const exported = state.app.exportedApplication as JsonObject;

    switch (name) {
      case "list_workspaces":
        tool({ workspaces: [{ id: "ws1", name: "Apps" }] });
        break;
      case "list_applications":
        tool([{ id: "app1", name: exported.name, slug: exported.slug }]);
        break;
      case "export_application":
        tool(state.app);
        break;
      case "import_application":
        state.imports.push({ applicationId: args.applicationId, artifact: args.artifact });
        state.app = importOver(state.app, args.artifact);
        tool({ applicationId: "app1", workspaceId: "ws1", name: exported.name, unconfiguredDatasources: [] });
        break;
      case "submit_for_approval":
        state.submits.push(args);
        tool({ status: "SUBMITTED", version: "0123456789ab" });
        break;
      default:
        answer({ content: [{ type: "text", text: `unknown tool ${name}` }], isError: true });
    }
  });
}

describe("cab-sync commands", () => {
  let server: Server;
  let env: NodeJS.ProcessEnv;
  let state: FakeState;
  let repo: string;
  let output: string[];
  const io = {
    out: (line: string) => output.push(line),
    err: (line: string) => output.push(`ERR ${line}`),
  };

  before(async () => {
    state = { app: sampleApp(), imports: [], submits: [], headers: [] };
    server = fakeServer(state);
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    env = { CAB_URL: `http://127.0.0.1:${(server.address() as AddressInfo).port}/`, CAB_TOKEN: TOKEN };
  });

  after(async () => {
    await new Promise((resolve) => server.close(resolve));
    await rm(TMP, { recursive: true, force: true });
  });

  beforeEach(async () => {
    state.app = sampleApp();
    state.imports.length = 0;
    state.submits.length = 0;
    state.headers.length = 0;
    output = [];
    repo = join(TMP, `repo-${Date.now()}-${Math.random().toString(16).slice(2)}`);
    await mkdir(repo, { recursive: true });
  });

  it("pull, edit, push, pull gives byte-identical files and changes only the edit copy", async () => {
    assert.equal(await run(["pull", "Orders Admin", "--repo", repo], env, io), 0);

    const dir = join(repo, "apps", "orders-admin");

    await writeFile(join(dir, "pages", "Orders", "queries", "GetOrders.sql"), "SELECT 3;\n", "utf8");

    const first = await readTree(dir);

    assert.equal(await run(["push", dir], env, io), 0);
    assert.equal(state.imports[0].applicationId, "app1");
    assert.equal((state.imports[0].artifact.exportedApplication as JsonObject).publishedPages, undefined);

    assert.equal(await run(["pull", dir], env, io), 0);
    assert.deepEqual([...(await readTree(dir))].sort(), [...first].sort());
  });

  it("push without changes imports nothing", async () => {
    await run(["pull", "Orders Admin", "--repo", repo], env, io);
    output = [];

    assert.equal(await run(["push", join(repo, "apps", "orders-admin")], env, io), 0);
    assert.equal(state.imports.length, 0);
    assert.match(output.join("\n"), /Nothing to push/);
  });

  it("pushes an edited query body and diff then finds nothing", async () => {
    await run(["pull", "orders-admin", "--repo", repo], env, io);

    const dir = join(repo, "apps", "orders-admin");
    const sql = join(dir, "pages", "Orders", "queries", "GetOrders.sql");

    await writeFile(sql, "SELECT id FROM orders;\n", "utf8");
    assert.equal(await run(["push", dir], env, io), 0);

    const pushed = (state.imports[0].artifact.actionList as JsonObject[]).find(
      (action) => (action.unpublishedAction as JsonObject).name === "GetOrders",
    ) as JsonObject;

    assert.equal(
      ((pushed.unpublishedAction as JsonObject).actionConfiguration as JsonObject).body,
      "SELECT id FROM orders;\n",
    );

    output = [];
    assert.equal(await run(["diff", dir], env, io), 0);
    assert.match(output.join("\n"), /matches the edit copy/);
  });

  it("diff lists the files that changed on the instance and exits 1", async () => {
    await run(["pull", "Orders Admin", "--repo", repo], env, io);

    const dir = join(repo, "apps", "orders-admin");
    const query = (state.app.actionList as JsonObject[])[0].unpublishedAction as JsonObject;

    (query.actionConfiguration as JsonObject).body = "SELECT 2";
    output = [];

    assert.equal(await run(["diff", dir], env, io), 1);
    assert.match(output.join("\n"), /modified\s+pages\/Orders\/queries\/GetOrders\.sql/);
  });

  it("submit sends the note and never needs the internal secret", async () => {
    await run(["pull", "Orders Admin", "--repo", repo], env, io);

    assert.equal(
      await run(["submit", join(repo, "apps", "orders-admin"), "--note", "New filter"], env, io),
      0,
    );
    assert.deepEqual(state.submits, [{ applicationId: "app1", note: "New filter" }]);
    assert.ok(state.headers.every((headers) => headers["x-appsmith-mcp-internal"] === undefined));
  });

  it("never writes the token to disk", async () => {
    await run(["init", repo], env, io);
    await run(["pull", "Orders Admin", "--repo", repo], env, io);

    for (const content of (await readTree(repo)).values()) assert.ok(!content.includes(TOKEN));
  });

  it("explains a missing token or URL instead of calling the server", async () => {
    assert.equal(await run(["pull", "Orders Admin"], { CAB_URL: env.CAB_URL }, io), 2);
    assert.match(output.join("\n"), /CAB_TOKEN/);
    assert.equal(await run(["pull", "Orders Admin"], { CAB_URL: env.CAB_URL, CAB_TOKEN: "abc" }, io), 2);
    assert.equal(state.headers.length, 0);
  });

  it("reports a refused token", async () => {
    assert.equal(await run(["pull", "Orders Admin"], { ...env, CAB_TOKEN: "mcp_wrong" }, io), 1);
    assert.match(output.join("\n"), /refused the token/);
  });

  it("init writes the template and keeps existing files", async () => {
    await writeFile(join(repo, "README.md"), "mine\n", "utf8");

    assert.equal(await run(["init", repo], env, io), 0);
    assert.equal(await readFile(join(repo, "README.md"), "utf8"), "mine\n");
    assert.match(await readFile(join(repo, "CLAUDE.md"), "utf8"), /cab-sync push/);
    assert.match(await readFile(join(repo, ".gitattributes"), "utf8"), /-text/);
  });
});

describe("helpers", () => {
  it("parseArgs reads flags in both forms", () => {
    const parsed = parseArgs(["submit", "apps/x", "--note=a = b", "--force"]);

    assert.equal(parsed.command, "submit");
    assert.deepEqual(parsed.positional, ["apps/x"]);
    assert.equal(parsed.flags.get("note"), "a = b");
    assert.equal(parsed.flags.get("force"), true);
    assert.throws(() => parseArgs(["pull", "--nope"]), /Unknown option/);
  });

  it("slugify makes folder names", () => {
    assert.equal(slugify("Orders Admin"), "orders-admin");
    assert.equal(slugify("Café / Bar!"), "cafe-bar");
    assert.equal(slugify("???"), "app");
  });

  it("parseEventStream reads every event's data", () => {
    assert.deepEqual(
      parseEventStream('event: message\r\ndata: {"jsonrpc":"2.0","id":1}\r\n\r\ndata: {"jsonrpc":"2.0",\ndata: "id":2}\n\n'),
      [
        { jsonrpc: "2.0", id: 1 },
        { jsonrpc: "2.0", id: 2 },
      ],
    );
  });
});

import { access } from "node:fs/promises";
import { basename, join, resolve } from "node:path";
import { readTree, writeTree } from "./files.ts";
import { isObject, type JsonObject } from "./json.ts";
import { assembleApp, diffFiles, splitApp, type FileMap } from "./layout.ts";
import { McpClient, McpError, mcpEndpoint } from "./mcp.ts";
import { writeTemplate } from "./template.ts";

export const USAGE = `cab-sync: move CreativeAppBuilder apps between the projects repo and the instance.

Usage:
  cab-sync init <dir> [--force]          write the projects repo template (README.md, CLAUDE.md, .gitignore)
  cab-sync pull <app> [--repo <dir>]     the app's edit copy into <repo>/apps/<app-slug>/ (or into <app> when
                                         <app> is an app folder); <app> is a name, slug or id
  cab-sync push <app-dir>                the folder into the app's edit copy (creates the app if there is none
                                         with its name); users keep seeing the published version
  cab-sync diff <app-dir>                the files that differ between the folder and the instance's edit copy
                                         (exit code 1 when there are any)
  cab-sync submit <app-dir> [--note <text>]   submit the app for approval; an Approver approves it in the app

Environment:
  CAB_URL        the instance, e.g. http://localhost:8880 (the MCP endpoint is <CAB_URL>/mcp)
  CAB_TOKEN      your MCP key (mcp_...); never written to disk
  CAB_WORKSPACE  the workspace name or id, when you can see more than one
`;

export interface Io {
  out: (line: string) => void;
  err: (line: string) => void;
}

export class UsageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UsageError";
  }
}

interface ParsedArgs {
  command: string | undefined;
  positional: string[];
  flags: Map<string, string | true>;
}

const VALUE_FLAGS = new Set(["repo", "note"]);
const SWITCH_FLAGS = new Set(["force", "help"]);

export function parseArgs(argv: string[]): ParsedArgs {
  const positional: string[] = [];
  const flags = new Map<string, string | true>();

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];

    if (arg === "-h") {
      flags.set("help", true);
    } else if (arg.startsWith("--")) {
      const [name, inline] = arg.slice(2).split(/=(.*)/s, 2);

      if (VALUE_FLAGS.has(name)) {
        const value = inline ?? argv[++i];

        if (value === undefined) throw new UsageError(`--${name} needs a value.`);

        flags.set(name, value);
      } else if (SWITCH_FLAGS.has(name)) {
        flags.set(name, true);
      } else {
        throw new UsageError(`Unknown option --${name}.`);
      }
    } else {
      positional.push(arg);
    }
  }

  return { command: positional.shift(), positional, flags };
}

interface Workspace {
  id: string;
  name: string;
}

interface AppSummary {
  id: string;
  name: string;
  slug: string | undefined;
}

export function slugify(name: string): string {
  const slug = name
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");

  return slug.length > 0 ? slug : "app";
}

async function exists(path: string): Promise<boolean> {
  try {
    await access(path);

    return true;
  } catch {
    return false;
  }
}

function connection(env: NodeJS.ProcessEnv): { url: string; token: string } {
  const url = env.CAB_URL?.trim();
  const token = env.CAB_TOKEN?.trim();

  if (!url) throw new UsageError("Set CAB_URL to the instance URL, e.g. http://localhost:8880.");

  if (!token) {
    throw new UsageError("Set CAB_TOKEN to your MCP key (Appsmith > profile > MCP keys, starts with mcp_).");
  }

  if (!token.startsWith("mcp_")) throw new UsageError("CAB_TOKEN must be an MCP key: it starts with mcp_.");

  return { url: mcpEndpoint(url), token };
}

export type ClientFactory = (url: string, token: string) => McpClient;

const defaultClientFactory: ClientFactory = (url, token) => new McpClient({ url, token });

async function withClient<T>(
  env: NodeJS.ProcessEnv,
  factory: ClientFactory,
  work: (client: McpClient) => Promise<T>,
): Promise<T> {
  const { url, token } = connection(env);
  const client = factory(url, token);

  await client.connect();

  try {
    return await work(client);
  } finally {
    await client.close();
  }
}

async function resolveWorkspace(client: McpClient, env: NodeJS.ProcessEnv): Promise<Workspace> {
  const { workspaces } = await client.callJsonTool<{ workspaces: Workspace[] }>("list_workspaces", {});
  const wanted = env.CAB_WORKSPACE?.trim();

  if (wanted) {
    const match = workspaces.find(
      (workspace) => workspace.id === wanted || workspace.name.toLowerCase() === wanted.toLowerCase(),
    );

    if (match === undefined) {
      throw new UsageError(
        `No workspace "${wanted}" (CAB_WORKSPACE). You can see: ${workspaces.map((w) => w.name).join(", ")}.`,
      );
    }

    return match;
  }

  if (workspaces.length === 1) return workspaces[0];

  const apps = workspaces.find((workspace) => workspace.name === "Apps");

  if (apps !== undefined) return apps;

  throw new UsageError(
    `You can see ${workspaces.length} workspaces; set CAB_WORKSPACE to one of: ${workspaces.map((w) => w.name).join(", ")}.`,
  );
}

function appSummaries(listed: unknown): AppSummary[] {
  const list = Array.isArray(listed)
    ? listed
    : isObject(listed) && Array.isArray(listed.applications)
      ? listed.applications
      : [];

  return list.filter(isObject).flatMap((app) =>
    typeof app.id === "string" && typeof app.name === "string"
      ? [{ id: app.id, name: app.name, slug: typeof app.slug === "string" ? app.slug : undefined }]
      : [],
  );
}

async function listApps(client: McpClient, workspace: Workspace): Promise<AppSummary[]> {
  return appSummaries(
    await client.callJsonTool<unknown>("list_applications", { workspaceId: workspace.id }),
  );
}

function findApp(apps: AppSummary[], key: string): AppSummary | undefined {
  return (
    apps.find((app) => app.id === key) ??
    apps.find((app) => app.name === key) ??
    apps.find((app) => app.name.toLowerCase() === key.toLowerCase()) ??
    apps.find((app) => app.slug === key)
  );
}

function appName(appJson: JsonObject, where: string): string {
  const exported = appJson.exportedApplication;
  const name = isObject(exported) ? exported.name : undefined;

  if (typeof name !== "string" || name.length === 0) {
    throw new UsageError(`${where} has no exportedApplication.name.`);
  }

  return name;
}

async function readAppFolder(dir: string): Promise<{ files: FileMap; app: JsonObject; name: string }> {
  if (!(await exists(join(dir, "application.json")))) {
    throw new UsageError(`${dir} is not an app folder (it has no application.json). Run cab-sync pull first.`);
  }

  const files = await readTree(dir);
  const app = assembleApp(files);

  return { files, app, name: appName(app, join(dir, "application.json")) };
}

async function exportFiles(client: McpClient, applicationId: string): Promise<{ files: FileMap; app: JsonObject }> {
  const app = await client.callJsonTool<JsonObject>("export_application", { applicationId });

  return { files: splitApp(app), app };
}

async function requireApp(client: McpClient, env: NodeJS.ProcessEnv, name: string): Promise<AppSummary> {
  const workspace = await resolveWorkspace(client, env);
  const app = findApp(await listApps(client, workspace), name);

  if (app === undefined) {
    throw new UsageError(`No app named "${name}" in workspace ${workspace.name}. Push it first.`);
  }

  return app;
}

async function pull(args: ParsedArgs, env: NodeJS.ProcessEnv, io: Io, factory: ClientFactory): Promise<number> {
  const target = args.positional[0];

  if (target === undefined) throw new UsageError("pull needs an app name, slug or id, or an app folder.");

  const folderGiven = await exists(join(target, "application.json"));
  const key = folderGiven ? (await readAppFolder(target)).name : target;

  return withClient(env, factory, async (client) => {
    const workspace = await resolveWorkspace(client, env);
    const app = findApp(await listApps(client, workspace), key);

    if (app === undefined) throw new UsageError(`No app "${key}" in workspace ${workspace.name}.`);

    const { files } = await exportFiles(client, app.id);
    const repo = resolve(typeof args.flags.get("repo") === "string" ? (args.flags.get("repo") as string) : ".");
    const dir = folderGiven ? resolve(target) : join(repo, "apps", slugify(app.slug ?? app.name));
    const summary = await writeTree(dir, files);

    io.out(`Pulled "${app.name}" into ${dir}`);
    io.out(
      `  ${summary.written.length} written, ${summary.deleted.length} deleted, ${summary.unchanged} unchanged`,
    );

    for (const path of summary.written) io.out(`  W ${path}`);

    for (const path of summary.deleted) io.out(`  D ${path}`);

    return 0;
  });
}

async function push(args: ParsedArgs, env: NodeJS.ProcessEnv, io: Io, factory: ClientFactory): Promise<number> {
  const dir = args.positional[0];

  if (dir === undefined) throw new UsageError("push needs an app folder (apps/<app>).");

  const { app, name } = await readAppFolder(dir);

  return withClient(env, factory, async (client) => {
    const workspace = await resolveWorkspace(client, env);
    const existing = findApp(await listApps(client, workspace), name);
    const result = await client.callJsonTool<{
      applicationId: string;
      name: string;
      unconfiguredDatasources?: string[];
    }>("import_application", {
      workspaceId: workspace.id,
      ...(existing !== undefined ? { applicationId: existing.id } : {}),
      artifact: app,
    });

    io.out(
      existing !== undefined
        ? `Pushed ${basename(resolve(dir))} into the edit copy of "${result.name}" (${result.applicationId}). Users still see the published version.`
        : `Created app "${result.name}" (${result.applicationId}) from ${basename(resolve(dir))}. Nobody but builders sees it until a release is approved.`,
    );

    if (existing === undefined && result.name !== name) {
      io.err(
        `Warning: the instance named the new app "${result.name}", not "${name}"; set exportedApplication.name in application.json to "${result.name}" so the next push finds it.`,
      );
    }

    if ((result.unconfiguredDatasources ?? []).length > 0) {
      io.err(
        `Datasources without a configuration on the instance (an Admin must set them up): ${result.unconfiguredDatasources?.join(", ")}`,
      );
    }

    io.out("Next: test it in the editor's preview, then cab-sync submit when it is ready.");

    return 0;
  });
}

async function diff(args: ParsedArgs, env: NodeJS.ProcessEnv, io: Io, factory: ClientFactory): Promise<number> {
  const dir = args.positional[0];

  if (dir === undefined) throw new UsageError("diff needs an app folder (apps/<app>).");

  const { files, name } = await readAppFolder(dir);

  return withClient(env, factory, async (client) => {
    const app = await requireApp(client, env, name);
    const instance = await exportFiles(client, app.id);
    const changes = diffFiles(files, instance.files);

    if (changes.length === 0) {
      io.out(`${dir} matches the edit copy of "${app.name}".`);

      return 0;
    }

    const label = { modified: "modified", "only-in-repo": "only in repo", "only-in-instance": "only in instance" };

    for (const change of changes) io.out(`${label[change.change].padEnd(17)} ${change.path}`);

    io.out(`${changes.length} file(s) differ from the edit copy of "${app.name}".`);

    return 1;
  });
}

async function submit(args: ParsedArgs, env: NodeJS.ProcessEnv, io: Io, factory: ClientFactory): Promise<number> {
  const dir = args.positional[0];

  if (dir === undefined) throw new UsageError("submit needs an app folder (apps/<app>).");

  const { name } = await readAppFolder(dir);
  const note = args.flags.get("note");

  return withClient(env, factory, async (client) => {
    const app = await requireApp(client, env, name);
    const state = await client.callJsonTool<{ status: string; version: string | null }>("submit_for_approval", {
      applicationId: app.id,
      ...(typeof note === "string" && note.trim().length > 0 ? { note } : {}),
    });

    io.out(`Submitted "${app.name}" for approval: ${state.status}, version ${state.version ?? "-"}.`);
    io.out("An Approver approves or rejects it in the app. A push before that cancels the request.");

    return 0;
  });
}

async function init(args: ParsedArgs, io: Io): Promise<number> {
  const dir = args.positional[0];

  if (dir === undefined) throw new UsageError("init needs the projects repo folder.");

  const { written, kept } = await writeTemplate(resolve(dir), args.flags.get("force") === true);

  for (const path of written) io.out(`  W ${path}`);

  for (const path of kept) io.out(`  kept ${path} (exists; --force overwrites)`);

  io.out(`Projects repo template in ${resolve(dir)}.`);

  return 0;
}

/** Runs one command; answers the process exit code. */
export async function run(
  argv: string[],
  env: NodeJS.ProcessEnv,
  io: Io,
  factory: ClientFactory = defaultClientFactory,
): Promise<number> {
  try {
    const args = parseArgs(argv);

    if (args.command === undefined || args.command === "help" || args.flags.get("help") === true) {
      io.out(USAGE);

      return args.command === undefined && args.flags.get("help") !== true ? 2 : 0;
    }

    switch (args.command) {
      case "init":
        return await init(args, io);
      case "pull":
        return await pull(args, env, io, factory);
      case "push":
        return await push(args, env, io, factory);
      case "diff":
        return await diff(args, env, io, factory);
      case "submit":
        return await submit(args, env, io, factory);
      default:
        throw new UsageError(`Unknown command "${args.command}".`);
    }
  } catch (error) {
    if (error instanceof UsageError) {
      io.err(error.message);
      io.err("Run cab-sync --help for the commands.");

      return 2;
    }

    if (error instanceof McpError || (error instanceof Error && error.name === "LayoutError")) {
      io.err(error.message);

      return 1;
    }

    throw error;
  }
}

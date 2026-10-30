import { z } from "zod";
import type { AppsmithApi, ToolResult } from "../../app.js";
import type { ExtensionToolHost } from "../../ce/extensions/tools.js";
import {
  approvalApi,
  projectsRepoApi,
  type ApprovalApi,
  type ProjectsRepoApi,
} from "./api.js";
import { EXTENSION_TOOL_CATALOG } from "./catalog.js";

// Fork: the release-approval tools, submit_for_approval and get_approval_status, and the projects-repo tools,
// export_application and import_application. Their catalog entries and annotations are in ee/extensions/catalog.ts,
// the API methods in ee/extensions/api.ts. The contract is in ce/extensions/tools.ts.
// Approving and rejecting are for a human Approver in the Appsmith UI: there is no tool for either, the server's MCP
// allowlist does not list their routes, and the server refuses them for MCP tokens.
//
// No runtime import from app.ts here (app.ts imports this module); the API error is recognised by shape instead.

export type {
  ExtensionToolHost,
  ExtensionToolRegistrar,
} from "../../ce/extensions/tools.js";

// The same id shape core tools accept (idSchema in app.ts).
const idSchema = z
  .string()
  .trim()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9_-]+$/);

const applicationIdSchema = idSchema;

// The server keeps the note in the request's history (ApplicationApprovalService.MAX_NOTE_LENGTH).
const MAX_NOTE_LENGTH = 500;

// Below the /mcp request cap (MAX_REQUEST_BODY_BYTES in app.ts, 2 MB), which also carries the JSON-RPC envelope.
export const MAX_IMPORT_ARTIFACT_BYTES = 1536 * 1024;

const SUBMIT_DESCRIPTION =
  "Submit an application for release approval. Use ONLY after the developer has finished UAT (testing the app) and " +
  "has asked you to submit it; never submit on your own initiative. Pass the applicationId you work on (for a " +
  "git-connected app, the branched applicationId), and optionally a short note for the Approver (what changed). " +
  "This submits the app's CURRENT edit-mode version as a frozen " +
  "snapshot. Any later change to the app (in the editor, by an import, or through MCP) cancels the request " +
  "automatically, and the app must then be submitted again. Approving and rejecting are done by a human Approver in " +
  "the Appsmith UI and are not available via MCP; the app is published only when an Approver approves it. Returns " +
  "the approval state: status (DRAFT, SUBMITTED, APPROVING, APPROVED or REJECTED), version, who submitted or " +
  "reviewed it and " +
  "when, the reject comment, and the history.";

const STATUS_DESCRIPTION =
  "Read an application's release-approval state: status (DRAFT = not submitted, or the submitted version was " +
  "cancelled because the app changed; SUBMITTED = waiting for an Approver; APPROVING = an Approver approved it and " +
  "it is being published; APPROVED = approved and published; " +
  "REJECTED = rejected, with the Approver's comment), the submitted version, who submitted and reviewed it and when, " +
  "and the history (SUBMITTED, APPROVED, REJECTED and CANCELLED events). Pass the applicationId you work on. Use it " +
  "to report progress to the developer or to see why a request was cancelled or rejected.";

const EXPORT_DESCRIPTION =
  "Export an application's edit copy (the unpublished version you work on) as app JSON, the format " +
  "import_application takes and the projects repo (tools/cab-sync) splits into files. It never contains the " +
  "published copy, and datasources appear by name and plugin only: no host, URL, credential or other secret. Needs " +
  "edit access to the app.";

const IMPORT_DESCRIPTION =
  "Import app JSON (from export_application, or the projects repo's files put back together) into Appsmith. With " +
  "applicationId it replaces that app's EDIT COPY only: the published version users see stays exactly as it was, " +
  "and changes only when an Approver approves a release (test the app in the editor's preview, then " +
  "submit_for_approval when the developer asks). Any pending approval request is cancelled because the app changed. " +
  "Without applicationId it creates a new app in workspaceId, which nobody but builders sees until a release is " +
  "approved. Datasources are matched by name and plugin; an Admin configures any that do not exist yet. Returns the " +
  "applicationId, name, workspaceId and the datasources that still need a configuration.";

interface ApiErrorShape {
  name: "AppsmithApiError";
  status: number;
  errorCode?: string;
  message: string;
}

function isAppsmithApiError(error: unknown): error is ApiErrorShape {
  return (
    error instanceof Error &&
    error.name === "AppsmithApiError" &&
    typeof (error as { status?: unknown }).status === "number"
  );
}

type Operation = "submit" | "status" | "export" | "import";

const ERROR_CODES: Record<Operation, string> = {
  submit: "approval_submit_refused",
  status: "approval_status_unavailable",
  export: "export_refused",
  import: "import_refused",
};

// Agent-legible guidance per status. The core request() never forwards the server's error body (an egress guard), so
// the server's own message text is not available here; the status and the Appsmith error code are.
function guidance(operation: Operation, status: number): string | undefined {
  switch (operation) {
    case "export":
      if (status === 403 || status === 404) {
        return "No application with this id that the user can edit (exporting needs edit access). Check the applicationId; do not retry.";
      }

      return undefined;
    case "import":
      if (status === 400) {
        return "Appsmith refused the app JSON: it is not a valid app export (a required field is missing or malformed). Fix the files and import again.";
      }

      if (status === 403) {
        return "The user is not allowed to import this: it needs the Developer or Administrator role and edit access to the app, and only an Administrator may create a datasource the app uses that does not exist yet. Tell the user; do not retry.";
      }

      if (status === 404) {
        return "No workspace or application with this id that the user can edit. Check workspaceId and applicationId.";
      }

      return undefined;
    default:
      break;
  }

  switch (status) {
    case 400:
      return operation === "submit"
        ? "Appsmith refused the submission. Usually this version is already waiting for approval, or it is already approved and published, or someone changed the request at the same moment, or the note is longer than 500 characters. Call get_approval_status to see the current state; submit again only after the app has changed or was rejected."
        : "Appsmith refused to read the approval state; check the applicationId.";
    case 403:
      return operation === "submit"
        ? "The user is not allowed to submit this app for approval: submitting needs the Developer or Administrator role and edit access to the app. Tell the user; do not retry."
        : "The user is not allowed to read this app's approval state (it needs edit access to the app). Tell the user; do not retry.";
    case 404:
      return "No application with this id that the user can edit. Pass the applicationId you work on (for a git-connected app, the branched applicationId).";
    default:
      return undefined;
  }
}

async function call<Api>(
  host: ExtensionToolHost,
  operation: Operation,
  api: (host: ExtensionToolHost) => Api,
  run: (api: Api) => Promise<unknown>,
): Promise<ToolResult> {
  try {
    return host.result(await run(api(host)));
  } catch (error) {
    if (!isAppsmithApiError(error)) throw error;

    const advice = guidance(operation, error.status);

    // Anything without specific advice fails the way a core tool does: the thrown error becomes the tool error.
    if (advice === undefined) throw error;

    return host.result({
      error: advice,
      code: ERROR_CODES[operation],
      status: error.status,
      ...(error.errorCode !== undefined ? { errorCode: error.errorCode } : {}),
    });
  }
}

const approvals = (host: ExtensionToolHost): ApprovalApi =>
  approvalApi(host.api);
const projectsRepo = (host: ExtensionToolHost): ProjectsRepoApi =>
  projectsRepoApi(host.api);

// Top-level keys of an app export that carry the published copy, datasource details or server bookkeeping. The server
// already leaves them out (EditCopyJson.java); this repeats it so no future server change can send a secret through
// this tool.
const DROPPED_TOP_LEVEL_KEYS = [
  "decryptedFields",
  "datasourceConfigurationStructureList",
  "modifiedResources",
  "publishedTheme",
  "publishedPageOrder",
  "publishedDefaultPageName",
  "publishedLayoutmongoEscapedWidgets",
];

const DATASOURCE_KEPT_KEYS = ["name", "pluginId"];

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

// The export with each datasource reduced to name and plugin, and no datasource configuration on queries that use a
// saved datasource (an API query's own URL, headers and parameters stay: they are the query; its authentication not).
export function withoutDatasourceDetails(
  exported: Record<string, unknown>,
): Record<string, unknown> {
  const result: Record<string, unknown> = { ...exported };

  for (const key of DROPPED_TOP_LEVEL_KEYS) delete result[key];

  if (Array.isArray(result.datasourceList)) {
    result.datasourceList = result.datasourceList
      .filter(isRecord)
      .map((datasource) =>
        Object.fromEntries(
          DATASOURCE_KEPT_KEYS.filter(
            (key) => datasource[key] !== undefined && datasource[key] !== null,
          ).map((key) => [key, datasource[key]]),
        ),
      );
  }

  if (Array.isArray(result.actionList)) {
    result.actionList = result.actionList.map((action) => {
      if (!isRecord(action)) return action;

      const copy: Record<string, unknown> = { ...action };

      for (const side of ["unpublishedAction", "publishedAction"]) {
        const dto = copy[side];

        if (isRecord(dto) && isRecord(dto.datasource)) {
          copy[side] = {
            ...dto,
            datasource: datasourceReference(dto.datasource),
          };
        }
      }

      return copy;
    });
  }

  return result;
}

function omit(
  record: Record<string, unknown>,
  keys: readonly string[],
): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(record).filter(([key]) => !keys.includes(key)),
  );
}

function datasourceReference(
  datasource: Record<string, unknown>,
): Record<string, unknown> {
  const rest = omit(datasource, [
    "datasourceConfiguration",
    "decryptedFields",
    "datasourceStorages",
  ]);
  const configuration = datasource.datasourceConfiguration;
  const saved = typeof rest.id === "string" && rest.id.trim().length > 0;

  if (saved || !isRecord(configuration)) return rest;

  return {
    ...rest,
    datasourceConfiguration: omit(configuration, [
      "authentication",
      "sshProxy",
      "sshProxyEnabled",
    ]),
  };
}

const artifactSchema = z.record(z.unknown()).superRefine((artifact, ctx) => {
  let size: number;

  try {
    size = Buffer.byteLength(JSON.stringify(artifact), "utf8");
  } catch {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "artifact must be plain JSON",
    });

    return;
  }

  if (size > MAX_IMPORT_ARTIFACT_BYTES) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: `artifact must not exceed ${MAX_IMPORT_ARTIFACT_BYTES} bytes`,
    });
  }
});

// Registers a tool only while it is catalogued. In production all always are (approvals.test.ts and
// projectsRepo.test.ts check that they register); the guard is for core's extensions.test.ts, which in one test swaps
// in an empty catalog but keeps this module, and would otherwise see the host refuse these tools as uncatalogued.
function catalogued(name: string): boolean {
  return EXTENSION_TOOL_CATALOG.some((tool) => tool.name === name);
}

export const registerExtensionTools = (host: ExtensionToolHost): void => {
  if (catalogued("submit_for_approval")) {
    host.registerTool(
      "submit_for_approval",
      SUBMIT_DESCRIPTION,
      {
        applicationId: applicationIdSchema,
        note: z.string().trim().min(1).max(MAX_NOTE_LENGTH).optional(),
      },
      async ({ applicationId, note }) =>
        call(host, "submit", approvals, async (api) =>
          api.submitForApproval(applicationId, note),
        ),
    );
  }

  if (catalogued("get_approval_status")) {
    host.registerTool(
      "get_approval_status",
      STATUS_DESCRIPTION,
      { applicationId: applicationIdSchema },
      async ({ applicationId }) =>
        call(host, "status", approvals, async (api) =>
          api.getApprovalStatus(applicationId),
        ),
    );
  }

  if (catalogued("export_application")) {
    host.registerTool(
      "export_application",
      EXPORT_DESCRIPTION,
      { applicationId: applicationIdSchema },
      async ({ applicationId }) =>
        call(host, "export", projectsRepo, async (api) =>
          withoutDatasourceDetails(
            await api.exportApplicationEditCopy(applicationId),
          ),
        ),
    );
  }

  if (catalogued("import_application")) {
    host.registerTool(
      "import_application",
      IMPORT_DESCRIPTION,
      {
        workspaceId: idSchema,
        applicationId: applicationIdSchema.optional(),
        artifact: artifactSchema,
      },
      async ({ workspaceId, applicationId, artifact }) =>
        call(host, "import", projectsRepo, async (api) =>
          api.importApplicationEditCopy(workspaceId, applicationId, artifact),
        ),
    );
  }
};

// Every tool uses the core "always" gate, so there are no edition gates to resolve.
export const resolveExtensionGates: (
  api: AppsmithApi,
) => Promise<Readonly<Record<string, boolean>>> = async () => ({});

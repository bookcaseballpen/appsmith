import { z } from "zod";
import type { AppsmithApi, ToolResult } from "../../app.js";
import type { ExtensionToolHost } from "../../ce/extensions/tools.js";
import { approvalApi, type ApprovalApi } from "./api.js";
import { EXTENSION_TOOL_CATALOG } from "./catalog.js";

// Fork: the release-approval tools, submit_for_approval and get_approval_status. Their catalog entries and annotations
// are in ee/extensions/catalog.ts, the API methods in ee/extensions/api.ts. The contract is in ce/extensions/tools.ts.
// Approving and rejecting are for a human Approver in the Appsmith UI: there is no tool for either, the server's MCP
// allowlist does not list their routes, and the server refuses them for MCP tokens.
//
// No runtime import from app.ts here (app.ts imports this module); the API error is recognised by shape instead.

export type {
  ExtensionToolHost,
  ExtensionToolRegistrar,
} from "../../ce/extensions/tools.js";

// The same id shape core tools accept (idSchema in app.ts).
const applicationIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9_-]+$/);

const SUBMIT_DESCRIPTION =
  "Submit an application for release approval. Use ONLY after the developer has finished UAT (testing the app) and " +
  "has asked you to submit it; never submit on your own initiative. Pass the applicationId you work on (for a " +
  "git-connected app, the branched applicationId). This submits the app's CURRENT edit-mode version as a frozen " +
  "snapshot. Any later change to the app (in the editor, by an import, or through MCP) cancels the request " +
  "automatically, and the app must then be submitted again. Approving and rejecting are done by a human Approver in " +
  "the Appsmith UI and are not available via MCP; the app is published only when an Approver approves it. Returns " +
  "the approval state: status (DRAFT, SUBMITTED, APPROVED or REJECTED), version, who submitted or reviewed it and " +
  "when, the reject comment, and the history.";

const STATUS_DESCRIPTION =
  "Read an application's release-approval state: status (DRAFT = not submitted, or the submitted version was " +
  "cancelled because the app changed; SUBMITTED = waiting for an Approver; APPROVED = approved and published; " +
  "REJECTED = rejected, with the Approver's comment), the submitted version, who submitted and reviewed it and when, " +
  "and the history (SUBMITTED, APPROVED, REJECTED and CANCELLED events). Pass the applicationId you work on. Use it " +
  "to report progress to the developer or to see why a request was cancelled or rejected.";

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

type Operation = "submit" | "status";

// Agent-legible guidance per status. The core request() never forwards the server's error body (an egress guard), so
// the server's own message text is not available here; the status and the Appsmith error code are.
function guidance(operation: Operation, status: number): string | undefined {
  switch (status) {
    case 400:
      return operation === "submit"
        ? "Appsmith refused the submission. Usually this version is already waiting for approval, or it is already approved and published, or someone changed the request at the same moment. Call get_approval_status to see the current state; submit again only after the app has changed or was rejected."
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

async function call(
  host: ExtensionToolHost,
  operation: Operation,
  run: (api: ApprovalApi) => Promise<unknown>,
): Promise<ToolResult> {
  try {
    return host.result(await run(approvalApi(host.api)));
  } catch (error) {
    if (!isAppsmithApiError(error)) throw error;

    const advice = guidance(operation, error.status);

    // Anything without specific advice fails the way a core tool does: the thrown error becomes the tool error.
    if (advice === undefined) throw error;

    return host.result({
      error: advice,
      code:
        operation === "submit"
          ? "approval_submit_refused"
          : "approval_status_unavailable",
      status: error.status,
      ...(error.errorCode !== undefined ? { errorCode: error.errorCode } : {}),
    });
  }
}

// Registers a tool only while it is catalogued. In production both always are (approvals.test.ts checks that both
// register); the guard is for core's extensions.test.ts, which in one test swaps in an empty catalog but keeps this
// module, and would otherwise see the host refuse these tools as uncatalogued.
function catalogued(name: string): boolean {
  return EXTENSION_TOOL_CATALOG.some((tool) => tool.name === name);
}

export const registerExtensionTools = (host: ExtensionToolHost): void => {
  if (!catalogued("submit_for_approval") || !catalogued("get_approval_status")) {
    return;
  }

  host.registerTool(
    "submit_for_approval",
    SUBMIT_DESCRIPTION,
    { applicationId: applicationIdSchema },
    async ({ applicationId }) =>
      call(host, "submit", async (api) =>
        api.submitForApproval(applicationId),
      ),
  );

  host.registerTool(
    "get_approval_status",
    STATUS_DESCRIPTION,
    { applicationId: applicationIdSchema },
    async ({ applicationId }) =>
      call(host, "status", async (api) =>
        api.getApprovalStatus(applicationId),
      ),
  );
};

// Both tools use the core "always" gate, so there are no edition gates to resolve.
export const resolveExtensionGates: (
  api: AppsmithApi,
) => Promise<Readonly<Record<string, boolean>>> = async () => ({});

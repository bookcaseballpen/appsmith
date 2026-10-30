import type { ApiRequest } from "../../ce/extensions/api.js";

// Fork: the endpoints behind the fork's extension tools (ee/extensions/tools.ts): the release-approval endpoints
// (submit_for_approval, get_approval_status) and the projects repo's app JSON (export_application,
// import_application). The contract is in ce/extensions/api.ts: every call goes through the handed `request`, and
// every endpoint is allowed for MCP principals by the server's McpAllowlistExtensions and listed in
// ee/extensions/routeContract.ts. Approving and rejecting are for people only, so there is deliberately no method for
// them.

export type { ApiRequest } from "../../ce/extensions/api.js";

// APPROVING: an approver's approve is publishing the submitted version right now.
export type ApprovalStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVING"
  | "APPROVED"
  | "REJECTED";

export interface ApprovalEvent {
  type: "SUBMITTED" | "APPROVED" | "REJECTED" | "CANCELLED";
  by: string | null;
  // Times are epoch seconds (with a fraction), as the server writes Instants.
  at: number | null;
  // The reject comment, the submitter's note, or why a request was cancelled.
  comment: string | null;
}

// The server's ApplicationApprovalDTO (approvals/ApplicationApprovalDTO.java).
export interface ApplicationApprovalState {
  applicationId: string;
  applicationName: string;
  workspaceId: string;
  defaultBasePageId: string | null;
  status: ApprovalStatus;
  // A short id of the frozen snapshot; null while DRAFT.
  version: string | null;
  submittedBy: string | null;
  submittedAt: number | null;
  reviewedBy: string | null;
  reviewedAt: number | null;
  // The approver's comment, only while REJECTED.
  comment: string | null;
  history: ApprovalEvent[];
}

export interface ApprovalApi {
  getApprovalStatus: (
    applicationId: string,
  ) => Promise<ApplicationApprovalState>;
  submitForApproval: (
    applicationId: string,
    note?: string,
  ) => Promise<ApplicationApprovalState>;
}

// The server's ProjectsRepoService.ImportResult.
export interface ImportResult {
  applicationId: string;
  workspaceId: string;
  name: string;
  // Datasources the app uses that have no configuration on this instance yet (an Admin configures them).
  unconfiguredDatasources: string[];
  // True when nothing was imported: the app JSON was the app's edit copy already (an import would reset its approval).
  unchanged: boolean;
}

export interface ProjectsRepoApi {
  // The app's edit copy as app JSON: no published copy, datasources by name and plugin only.
  exportApplicationEditCopy: (
    applicationId: string,
  ) => Promise<Record<string, unknown>>;
  // A new app (applicationId undefined), or over the edit copy of an existing one.
  importApplicationEditCopy: (
    workspaceId: string,
    applicationId: string | undefined,
    artifact: Record<string, unknown>,
  ) => Promise<ImportResult>;
}

// Optional on AppsmithApi: core's test suites build AppsmithApi stubs listing only the core methods, so required
// edition methods would fail their type check. createExtensionApi always supplies all of them; tools reach them
// through approvalApi() and projectsRepoApi(), which fail loudly if a stub without them is used.
export type ExtensionApi = Partial<ApprovalApi & ProjectsRepoApi>;

export function approvalApi(api: ExtensionApi): ApprovalApi {
  const { getApprovalStatus, submitForApproval } = api;

  if (getApprovalStatus === undefined || submitForApproval === undefined) {
    throw new Error("This Appsmith API client has no release-approval methods");
  }

  return { getApprovalStatus, submitForApproval };
}

export function projectsRepoApi(api: ExtensionApi): ProjectsRepoApi {
  const { exportApplicationEditCopy, importApplicationEditCopy } = api;

  if (
    exportApplicationEditCopy === undefined ||
    importApplicationEditCopy === undefined
  ) {
    throw new Error("This Appsmith API client has no projects-repo methods");
  }

  return { exportApplicationEditCopy, importApplicationEditCopy };
}

const APPROVAL_APPLICATIONS = "/api/v1/approvals/applications";
const PROJECTS_REPO_APPLICATIONS = "/api/v1/projects-repo/applications";

// An export or import reads or writes the whole app, like a git commit does, so it gets the same budget.
const APP_JSON_TIMEOUT_MS = 120 * 1000;

export const createExtensionApi = (
  request: ApiRequest,
): ApprovalApi & ProjectsRepoApi => ({
  // Both opt into the Appsmith error code, so a refusal surfaces as e.g. "Appsmith API request failed (400)
  // [AE-...]" rather than a bare status (the collection mutations in app.ts do the same).
  getApprovalStatus: async (applicationId) =>
    request<ApplicationApprovalState>(
      `${APPROVAL_APPLICATIONS}/${encodeURIComponent(applicationId)}`,
      undefined,
      { extractErrorCode: true },
    ),
  submitForApproval: async (applicationId, note) =>
    request<ApplicationApprovalState>(
      `${APPROVAL_APPLICATIONS}/${encodeURIComponent(applicationId)}/submit`,
      note === undefined
        ? { method: "POST" }
        : { method: "POST", body: JSON.stringify({ note }) },
      { extractErrorCode: true },
    ),
  exportApplicationEditCopy: async (applicationId) =>
    request<Record<string, unknown>>(
      `${PROJECTS_REPO_APPLICATIONS}/${encodeURIComponent(applicationId)}`,
      undefined,
      { extractErrorCode: true, timeoutMs: APP_JSON_TIMEOUT_MS },
    ),
  importApplicationEditCopy: async (workspaceId, applicationId, artifact) =>
    request<ImportResult>(
      `${PROJECTS_REPO_APPLICATIONS}/import/${encodeURIComponent(workspaceId)}` +
        (applicationId === undefined
          ? ""
          : `?applicationId=${encodeURIComponent(applicationId)}`),
      { method: "POST", body: JSON.stringify(artifact) },
      { extractErrorCode: true, timeoutMs: APP_JSON_TIMEOUT_MS },
    ),
});

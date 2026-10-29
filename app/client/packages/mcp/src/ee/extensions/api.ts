import type { ApiRequest } from "../../ce/extensions/api.js";

// Fork: the release-approval endpoints behind submit_for_approval and get_approval_status (ee/extensions/tools.ts).
// The contract is in ce/extensions/api.ts: every call goes through the handed `request`, and every endpoint is allowed
// for MCP principals by the server's McpAllowlistExtensions and listed in ee/extensions/routeContract.ts. Approving
// and rejecting are for people only, so there is deliberately no method for them.

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
  getApprovalStatus: (applicationId: string) => Promise<ApplicationApprovalState>;
  submitForApproval: (applicationId: string) => Promise<ApplicationApprovalState>;
}

// Optional on AppsmithApi: core's test suites build AppsmithApi stubs listing only the core methods, so required
// edition methods would fail their type check. createExtensionApi always supplies both; tools reach them through
// approvalApi(), which fails loudly if a stub without them is used.
export type ExtensionApi = Partial<ApprovalApi>;

export function approvalApi(api: ExtensionApi): ApprovalApi {
  const { getApprovalStatus, submitForApproval } = api;

  if (getApprovalStatus === undefined || submitForApproval === undefined) {
    throw new Error("This Appsmith API client has no release-approval methods");
  }

  return { getApprovalStatus, submitForApproval };
}

const APPROVAL_APPLICATIONS = "/api/v1/approvals/applications";

export const createExtensionApi = (request: ApiRequest): ApprovalApi => ({
  // Both opt into the Appsmith error code, so a refusal surfaces as e.g. "Appsmith API request failed (400)
  // [AE-...]" rather than a bare status (the collection mutations in app.ts do the same).
  getApprovalStatus: async (applicationId) =>
    request<ApplicationApprovalState>(
      `${APPROVAL_APPLICATIONS}/${encodeURIComponent(applicationId)}`,
      undefined,
      { extractErrorCode: true },
    ),
  submitForApproval: async (applicationId) =>
    request<ApplicationApprovalState>(
      `${APPROVAL_APPLICATIONS}/${encodeURIComponent(applicationId)}/submit`,
      { method: "POST" },
      { extractErrorCode: true },
    ),
});

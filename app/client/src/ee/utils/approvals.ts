import type {
  ApplicationApproval,
  ApprovalStatus,
} from "ee/api/ApprovalApi";
import type { InstanceRole } from "ee/utils/instanceRoles";

/**
 * Fork: helpers for the release approval flow. The server enforces who may submit, approve or reject; the client only
 * shows each role its own controls.
 */

export const APPROVAL_STATUS_LABELS: Record<ApprovalStatus, string> = {
  DRAFT: "Draft",
  SUBMITTED: "Waiting for approval",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

/** Devs submit instead of deploying; Admins deploy directly (and Approvers deploy what they reviewed). */
export const submitsForApproval = (role?: InstanceRole) => role === "DEV";

/** Approvers and Admins approve or reject submitted releases. */
export const reviewsReleases = (role?: InstanceRole) =>
  role === "APPROVER" || role === "ADMIN";

/** The reason the last request was cancelled, while the app is a draft again. */
export const getCancellationReason = (approval?: ApplicationApproval | null) => {
  if (!approval || approval.status !== "DRAFT") return undefined;

  const last = approval.history[approval.history.length - 1];

  return last?.type === "CANCELLED" ? last.comment || undefined : undefined;
};

/** The editor opens read-only for Approvers, so the submitted (edit-mode) version is what they preview there. */
export const getApprovalPreviewPath = (approval: ApplicationApproval) =>
  approval.defaultBasePageId
    ? `/app/application/page-${approval.defaultBasePageId}/edit`
    : undefined;

export const formatApprovalTime = (value?: string | null) =>
  value ? new Date(value).toLocaleString() : "";

// Responses come back unwrapped by the axios interceptors: { responseMeta, data }, also for 4xx answers.
interface UnwrappedApiResponse<T> {
  responseMeta?: { success: boolean; error?: { message?: string } };
  data: T;
}

/** Runs an approval API call and answers its data, or throws with the server's message. */
export async function callApprovalApi<T>(
  call: () => Promise<unknown>,
  fallbackMessage: string,
): Promise<T> {
  let response: UnwrappedApiResponse<T> | undefined;

  try {
    response = (await call()) as UnwrappedApiResponse<T>;
  } catch (error) {
    const message = (error as { message?: string })?.message;

    throw new Error(message || fallbackMessage);
  }

  if (!response?.responseMeta?.success) {
    throw new Error(response?.responseMeta?.error?.message || fallbackMessage);
  }

  return response.data;
}

import type { AxiosPromise } from "axios";
import Api from "api/Api";
import type { ApiResponse } from "api/ApiResponses";

export type ApprovalStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED";

export interface ApprovalEvent {
  type: "SUBMITTED" | "APPROVED" | "REJECTED" | "CANCELLED";
  by?: string | null;
  at: string;
  comment?: string | null;
}

export interface ApplicationApproval {
  applicationId: string;
  applicationName: string;
  workspaceId: string;
  defaultBasePageId?: string | null;
  status: ApprovalStatus;
  version?: string | null;
  submittedBy?: string | null;
  submittedAt?: string | null;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  comment?: string | null;
  history: ApprovalEvent[];
}

/**
 * Fork: release approvals. A Dev submits the app's current version; an Approver approves it (which publishes it) or
 * rejects it with a comment. The server enforces the roles.
 */
export class ApprovalApi extends Api {
  static url = "v1/approvals";

  static async fetchStatus(
    applicationId: string,
  ): Promise<AxiosPromise<ApiResponse<ApplicationApproval>>> {
    return Api.get(`${ApprovalApi.url}/applications/${applicationId}`);
  }

  static async fetchPending(): Promise<
    AxiosPromise<ApiResponse<ApplicationApproval[]>>
  > {
    return Api.get(`${ApprovalApi.url}/pending`);
  }

  static async submit(
    applicationId: string,
  ): Promise<AxiosPromise<ApiResponse<ApplicationApproval>>> {
    return Api.post(`${ApprovalApi.url}/applications/${applicationId}/submit`);
  }

  static async approve(
    applicationId: string,
  ): Promise<AxiosPromise<ApiResponse<ApplicationApproval>>> {
    return Api.post(`${ApprovalApi.url}/applications/${applicationId}/approve`);
  }

  static async reject(
    applicationId: string,
    comment: string,
  ): Promise<AxiosPromise<ApiResponse<ApplicationApproval>>> {
    return Api.post(`${ApprovalApi.url}/applications/${applicationId}/reject`, {
      comment,
    });
  }
}

export default ApprovalApi;

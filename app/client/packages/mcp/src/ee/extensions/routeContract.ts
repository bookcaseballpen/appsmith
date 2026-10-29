import type { ExtensionRouteContract } from "../../ce/extensions/routeContract.js";
import { approvalApi } from "./api.js";

// Fork: the release-approval API methods (ee/extensions/api.ts) for the route-contract test. Test support only; the
// contract is in ce/extensions/routeContract.ts.

export type { ExtensionRouteContract } from "../../ce/extensions/routeContract.js";

export const EXTENSION_ROUTE_CONTRACT: ExtensionRouteContract = {
  invocations: (api, param) => ({
    getApprovalStatus: async () => approvalApi(api).getApprovalStatus(param),
    submitForApproval: async () => approvalApi(api).submitForApproval(param),
  }),
  controllerFiles: ["approvals/ApplicationApprovalController.java"],
  // Url.APPROVAL_URL in constants/Url.java (the fork's, not UrlCE).
  urlBases: { APPROVAL_URL: "/api/v1/approvals" },
};

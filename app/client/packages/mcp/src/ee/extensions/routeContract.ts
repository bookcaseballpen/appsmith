import type { ExtensionRouteContract } from "../../ce/extensions/routeContract.js";
import { appTagsApi, approvalApi, projectsRepoApi } from "./api.js";

// Fork: the extension API methods (ee/extensions/api.ts) for the route-contract test. Test support only; the
// contract is in ce/extensions/routeContract.ts.

export type { ExtensionRouteContract } from "../../ce/extensions/routeContract.js";

export const EXTENSION_ROUTE_CONTRACT: ExtensionRouteContract = {
  invocations: (api, param) => ({
    getApprovalStatus: async () => approvalApi(api).getApprovalStatus(param),
    submitForApproval: async () => approvalApi(api).submitForApproval(param),
    exportApplicationEditCopy: async () =>
      projectsRepoApi(api).exportApplicationEditCopy(param),
    importApplicationEditCopy: async () =>
      projectsRepoApi(api).importApplicationEditCopy(param, param, {
        artifactJsonType: "APPLICATION",
      }),
    setApplicationTags: async () =>
      appTagsApi(api).setApplicationTags(param, ["tag"]),
  }),
  controllerFiles: [
    "approvals/ApplicationApprovalController.java",
    "projectsrepo/ProjectsRepoController.java",
    "catalog/AppTagsController.java",
  ],
  // Url.APPROVAL_URL, Url.PROJECTS_REPO_URL and Url.APP_TAGS_URL in constants/Url.java (the fork's, not UrlCE).
  urlBases: {
    APPROVAL_URL: "/api/v1/approvals",
    PROJECTS_REPO_URL: "/api/v1/projects-repo",
    APP_TAGS_URL: "/api/v1/app-tags",
  },
};

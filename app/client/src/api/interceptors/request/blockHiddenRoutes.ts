import type { InternalAxiosRequestConfig } from "axios";

// Fork: the template gallery is hidden (ee/utils/forkHiddenFeatures) and the server refuses /api/v1/app-templates, so
// the client never sends a request there. Answered locally like an airgapped route (blockAirgappedRoutes).
export const HIDDEN_ROUTES_REGEX = /^\/?(api\/)?v1\/app-templates($|[/?])/;

const blockHiddenRoutes = (
  request: InternalAxiosRequestConfig,
  options: { templatesHidden: boolean },
) => {
  const { url } = request;

  if (options.templatesHidden && url && HIDDEN_ROUTES_REGEX.test(url)) {
    request.adapter = async (config) => {
      return new Promise((resolve) => {
        resolve({
          data: null,
          status: 200,
          statusText: "OK",
          headers: {},
          config,
          request,
        });
      });
    };
  }

  return request;
};

export { blockHiddenRoutes };

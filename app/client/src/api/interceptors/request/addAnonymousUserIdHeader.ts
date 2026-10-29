import type { InternalAxiosRequestConfig } from "axios";

// Fork: no longer used by apiRequestInterceptor, since the anonymous id came from Segment.
export const addAnonymousUserIdHeader = (
  config: InternalAxiosRequestConfig,
  options: { anonymousId: string | null | undefined; segmentEnabled?: boolean },
) => {
  const { anonymousId, segmentEnabled } = options;

  config.headers = config.headers || {};

  if (segmentEnabled && anonymousId) {
    config.headers["x-anonymous-user-id"] = anonymousId;
  }

  return config;
};

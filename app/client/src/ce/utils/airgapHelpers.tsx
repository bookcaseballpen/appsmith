// Fork: URLs on assets.appsmith.com (for example plugin icons stored in the database) are served from the local copy
// in public/assets-cdn instead. See FORK.md.
const APPSMITH_ASSETS_HOST = /^https?:\/\/assets\.appsmith\.com(?=\/)/;

export const getAssetUrl = (src = "") => {
  // Some callers pass values typed loosely (for example from the database), so only rewrite strings.
  return typeof src === "string"
    ? src.replace(APPSMITH_ASSETS_HOST, "/assets-cdn")
    : src;
};

export const isAirgapped = () => {
  return false;
};

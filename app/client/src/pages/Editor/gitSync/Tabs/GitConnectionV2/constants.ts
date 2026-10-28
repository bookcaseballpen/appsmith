export const GIT_CONNECT_STEPS = {
  CHOOSE_PROVIDER: "choose-provider",
  GENERATE_SSH_KEY: "generate-ssh-key",
  ADD_DEPLOY_KEY: "add-deploy-key",
};

// Fork: the how-to GIFs lived on assets.appsmith.com (about 70 MB) and are not vendored, so the demos are hidden.
const NO_DEMO_GIF = { github: "", gitlab: "", bitbucket: "" };

export const GIT_DEMO_GIF: Record<
  "create_repo" | "copy_remoteurl" | "add_deploykey",
  Record<string, string>
> = {
  create_repo: NO_DEMO_GIF,
  copy_remoteurl: NO_DEMO_GIF,
  add_deploykey: NO_DEMO_GIF,
};

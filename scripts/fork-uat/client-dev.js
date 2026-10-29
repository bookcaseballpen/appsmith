// Starts the client's webpack dev server (app/client/scripts/start.js) for one
// worktree, without changing the worktree:
// - the HMR websocket port follows PORT (webpackDevServer.config.js hard-codes 3000,
//   so a second worktree's browser tab would get the first one's updates);
// - CI=true stops start.js from exiting when stdin closes (it runs detached).
//
// Usage: node client-dev.js <app/client folder>   (PORT and HOST from the environment)
"use strict";

const path = require("path");

const clientDir = path.resolve(process.argv[2]);
const port = Number(process.env.PORT);

process.chdir(clientDir);
process.env.CI = "true";
process.env.BROWSER = "none";

const configPath = require.resolve(
  path.join(clientDir, "config", "webpackDevServer.config.js"),
);
const createDevServerConfig = require(configPath);
require.cache[configPath].exports = (...args) => {
  const config = createDevServerConfig(...args);
  config.client.webSocketURL.port = port;
  return config;
};

require(path.join(clientDir, "scripts", "start.js"));

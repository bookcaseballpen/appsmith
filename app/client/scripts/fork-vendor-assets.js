// Fork: copies every runtime asset referenced on assets.appsmith.com into public/assets-cdn/<path>, so the
// running product never loads from Appsmith's CDN. getAssetUrl() and ASSETS_CDN_URL point at /assets-cdn.
// Run it by hand after an upstream cherry-pick adds a new asset, then commit the new files. See FORK.md.
// Usage (from the repo root): node app/client/scripts/fork-vendor-assets.js [--dry]
const fs = require("fs");
const path = require("path");
const https = require("https");

const root = path.resolve(__dirname, "../../..");
const dry = process.argv.includes("--dry");
const scanDirs = [
  "app/client/src",
  "app/client/public",
  "app/client/packages/dsl/src",
  "app/server/appsmith-server/src/main",
  "app/server/appsmith-plugins",
  "deploy/docker/fs",
].map((d) => path.join(root, d));
const exts = new Set([
  ".ts",
  ".tsx",
  ".js",
  ".java",
  ".json",
  ".jsonl",
  ".html",
  ".css",
]);
const skipDirs = new Set([
  "node_modules",
  "target",
  "build",
  "assets-cdn",
  "__tests__",
  "test",
  "tests",
  "__mocks__",
  "mockData",
]);
const skipFile =
  /(\.test\.|\.stories\.|unitTestUtils|test_config|testData|fixture)/;
const re =
  /(?:\$\{ASSETS_CDN_URL\}|https:\/\/assets\.appsmith\.com)(\/[^`"'\s)\\]+)/g;
// Not vendored on purpose: the Git how-to GIFs are about 70 MB, so their demos are hidden instead.
const excluded = /\.gif$/;

const found = new Map();

function walk(dir) {
  if (!fs.existsSync(dir)) return;

  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);

    if (e.isDirectory()) {
      if (!skipDirs.has(e.name)) walk(full);
    } else if (exts.has(path.extname(e.name)) && !skipFile.test(e.name)) {
      const text = fs.readFileSync(full, "utf8");

      for (const m of text.matchAll(re)) {
        const p = m[1].replace(/\/+/g, "/");

        if (!excluded.test(p) && !found.has(p)) found.set(p, full);
      }
    }
  }
}

function get(url) {
  return new Promise((resolve) => {
    https
      .get(url, (res) => {
        if (
          res.statusCode >= 300 &&
          res.statusCode < 400 &&
          res.headers.location
        ) {
          res.resume();
          resolve(get(new URL(res.headers.location, url).toString()));

          return;
        }

        const chunks = [];

        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({ status: res.statusCode, body: Buffer.concat(chunks) }),
        );
      })
      .on("error", (err) => resolve({ status: 0, error: err.message }));
  });
}

(async () => {
  scanDirs.forEach(walk);

  const outRoot = path.join(root, "app/client/public/assets-cdn");
  const missing = [...found.keys()]
    .sort()
    .filter((p) => !fs.existsSync(path.join(outRoot, p)));

  console.log(`${found.size} asset paths, ${missing.length} not vendored yet`);

  for (const p of missing) {
    if (dry) {
      console.log(`${p}\t(${path.relative(root, found.get(p))})`);
      continue;
    }

    const res = await get(`https://assets.appsmith.com${p}`);

    if (res.status === 200) {
      const dest = path.join(outRoot, p);

      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, res.body);
      console.log(`ok\t${p}`);
    } else {
      console.log(`failed (${res.status || res.error})\t${p}`);
      process.exitCode = 1;
    }
  }
})();

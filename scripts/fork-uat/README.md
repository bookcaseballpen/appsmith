# Fork UAT: run any worktree of the fork on this PC (dev mode)

One command runs a worktree from source at `http://localhost:8080`, with client hot
reload, so a change can be reviewed in a browser.

```powershell
# From the repo or worktree to run (it must contain this folder):
powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\up.ps1                     # start everything
powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\up.ps1 -Restart server     # after Java changes
powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\down.ps1                   # stop; databases and data stay
```

## For AI tasks

- **Start** from the task's worktree, with a port set no other worktree uses
  (8080, 8180, 8280, ...; each instance uses its port and the next four):
  `powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\up.ps1 -Port 8180`.
  It returns when the server is healthy and the login page loads. The first start in a
  new worktree takes about 30 minutes (`yarn install` about 14, then the Maven build and
  the first webpack compile, about 15); later starts take about 5 minutes. A worktree
  with the main folder's `package.json` and `yarn.lock` skips `yarn install` (see
  "Client dependencies from the main folder").
- **URL:** `http://localhost:<Port>` (8080 by default). Bound to 127.0.0.1 only.
- **Sign in** with the dev-only admin, created on the worktree's empty database:
  - email `dev-admin@example.com`
  - password `DevAdmin#2026`

  Local use only; never use these on a shared or public instance.
- **Client changes** (`app/client/src`) show in the browser by themselves (hot reload).
  **Server changes** need `up.ps1 -Restart server` (see timings below).
- **Stop:** `powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\down.ps1`.
  This stops the client, server and proxy, and keeps MongoDB, Redis and all data.
  Don't delete data (`-DeleteData`) of an instance you didn't create.
- **Logs:** `scripts\fork-uat\.work\<Name>\` (`server.log`, `build.log`, `build-all.log`,
  `rts.log`, `client.log`).

## What runs where

| Part | Runs | Address | Data |
|---|---|---|---|
| MongoDB 7, replica set `rs0` | container `appsmith-dev-mongo`, shared by all worktrees | `127.0.0.1:27017` | volume `appsmith-dev-mongo`; one database per worktree, `appsmith_<Name>` |
| Redis 7 | container `appsmith-dev-<Name>-redis`, one per worktree | `127.0.0.1:<Port+4>` | volume `appsmith-dev-<Name>-redis` |
| Server (Spring Boot) | local JDK 25, built with local Maven | `127.0.0.1:<Port+1>` | settings in `.work\<Name>\docker.env`, Git repos in `.work\<Name>\git-storage` |
| RTS | local Node | `127.0.0.1:<Port+2>` | |
| Client (webpack dev server, hot reload) | local Node, the repo's Yarn 3 | `127.0.0.1:<Port+3>` | |
| Proxy (nginx) | container `appsmith-dev-<Name>-proxy` | `127.0.0.1:<Port>` | |

`<Name>` is the worktree folder name, lower-cased (e.g. `fork-roles-phase-a`), so
`C:\Projects\appsmith-wt\<branch>` worktrees each get their own instance. The proxy has the
routes of `app/client/start-https.sh` (`/api`, `/oauth2`, `/login` to the server, `/rts` to
RTS, the rest to webpack), over plain http.

MongoDB and Redis restart with Docker Desktop (`--restart unless-stopped`) and stay up
when the rest is stopped. The client, server and RTS run as hidden processes and stop
when you sign out or reboot; run `up.ps1` again.

**Why one Redis per worktree:** the server ignores the database number in
`APPSMITH_REDIS_URL` (`app/server/.../configurations/RedisConfig.java`), so worktrees
sharing one Redis would share sessions and caches. MongoDB is shared, with a database
per worktree.

## Parameters of `up.ps1`

| Parameter | Default | |
|---|---|---|
| `-Source` | repo containing the script | Repo or worktree folder to run. |
| `-Port` | `8080` | Browser port; the instance also uses the next four. |
| `-Name` | worktree folder name | Instance name (containers, volumes, `.work\<Name>`). |
| `-Database` | `appsmith_<Name>` | MongoDB or PostgreSQL database. |
| `-DbType` | `postgres` if the worktree has `db/migration/postgres` in appsmith-server, else `mongo` | Internal database. `postgres` runs a PostgreSQL 16 container for this instance (`appsmith-dev-<Name>-postgres`, `127.0.0.1:<Port+5>`, volume of the same name, dev-only user `appsmith` / password `appsmith-dev`); the server creates its schema on first start. MongoDB is then not used. Kept for `-Restart`; `down.ps1` handles both. |
| `-Only` | `db, server, client` | Run only some steps, e.g. `-Only db`. |
| `-Restart` | | `server` or `client`: rebuild and restart that part only. Keeps the port and database the instance was started with. |
| `-Full` | off | With the server: rebuild every module and the plugins. |
| `-Fast` | off | With the server: compile only the edited `.java` files (see below). |
| `-JavaHome` | detected | JDK 25 folder. Default: `JAVA_HOME` if it is a JDK 25, else the newest `jdk-25*` under Program Files (Microsoft, Eclipse Adoptium, Java, Zulu, Amazon Corretto). Set for the server and Maven only; the global `JAVA_HOME` stays as it is. |
| `-NoSeed` | off | Don't create the dev admin. Use it for a human UAT on a fresh database, so the first-time setup page appears. |
| `-NoLink` | off | Run `yarn install` in the worktree instead of linking the main folder's client dependencies; removes existing links first. |
| `-TimeoutMinutes` | `30` | How long to wait for the server and the first client compile. |

## Server changes and rebuild times

The server runs from its compiled classes (`appsmith-server\target\classes` plus a saved
runtime classpath), as in an IDE, not from a packaged jar. `up.ps1 -Restart server`:

1. compiles `appsmith-server` and the modules it uses
   (`mvn --projects appsmith-server --also-make compile`; unchanged modules compile nothing),
   and rebuilds the RTS bundle. The old server keeps serving meanwhile;
2. stops the old server and RTS and starts the new ones.

The plugins in `app\server\dist\plugins` are reused. Add `-Full` when a change touches
`appsmith-plugins` (or something in `appsmith-interfaces` that plugins use), or after a
pull that does.

`appsmith-server` is recompiled whole every time (about 45 s): the repo has empty `.java`
files (e.g. `domains/ModuleInstance.java`) that never produce a `.class`, so Maven always
sees the module as changed. `-Restart server -Fast` compiles only the edited files instead.
It's safe for edits inside method bodies; after changing a signature, a constant or a
class others use, or after a pull, leave `-Fast` off, since the classes that use it are not
recompiled.

The repo has no Spring DevTools, so there is no in-place reload of Java code.

Measured on this PC (8 cores, 2026-10-28):

| Step | Time |
|---|---|
| First full build, all modules and plugins (`--threads 1C`, Maven repository already filled) | 3:18 |
| `-Restart server`, one class edited | 1:25 (0:48 compile, 0:28 start) |
| `-Restart server -Fast`, one class edited | 0:54 (0:16 compile, 0:29 start) |
| Server start (process start to `/api/v1/health` 200) | 0:28–0:37 |
| Full `up.ps1` on a built worktree (server build, start, webpack compile from its cache) | 5:10 |
| First webpack compile in a new worktree (no cache) | about 15 min |
| Client hot reload after saving a `.tsx` file | 20–50 s |

For comparison, the earlier JDK 25 container took 9:10 for a restart.

## After pulling or switching branches

```powershell
git pull
powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\up.ps1 -Restart server -Full   # if server code changed
powershell -ExecutionPolicy Bypass -File .\scripts\fork-uat\up.ps1 -Restart client         # if yarn.lock changed
```

`up.ps1` runs `yarn install` when `yarn.lock` is newer than the last install. For client
code only, the running dev server picks up the change by itself.

## Client dependencies from the main folder

When a worktree has no `app\client\node_modules` and its `app\client\package.json` and
`yarn.lock` are identical to the main folder's (the folder above `git rev-parse
--git-common-dir`, e.g. `C:\Projects\appsmith`), and the main folder has a finished install
(`node_modules\.yarn-state.yml`), `up.ps1` links the main folder's dependencies instead of
running `yarn install`, and prints a line saying so. Otherwise it installs as before.

The link is not one junction for the whole folder (`client-deps.ps1`):

- `app\client\node_modules` is a real folder with one junction per entry of the main
  folder's `node_modules` (about 1,950 entries);
- Yarn's workspace links (`@appsmith/ads`, `@appsmith/wds`, `@shared/dsl`, `@shared/ast`,
  `appsmith-rts`, ...) are absolute junctions into the main folder's `packages\`; they are
  pointed at the worktree's own `packages\` instead, so the worktree builds its own code;
- `node_modules\.cache` (webpack, babel-loader and tsc caches) is the worktree's own, so two
  running dev servers never share a cache;
- the packages' own `node_modules` (`packages\ast`, `icons`, `mcp`, `rts`,
  `design-system\ads`, `design-system\headless`, `design-system\widgets`) are linked the
  same way;
- `node_modules\.fork-uat-linked` lists the linked folders.

Every `up.ps1` run checks the two files again. When they no longer match (a branch changed
the dependencies) or with `-NoLink`, it removes the links and runs a real `yarn install`.
Don't run `yarn install` yourself in a linked worktree: it would write through the
junctions into the main folder. A linked worktree changes when the main folder's install
does, so reinstall the main folder only while no linked worktree is running.

`down.ps1 -DeleteData` removes the links, never what they point to. Use it, or
`up.ps1 -NoLink`, rather than deleting `node_modules` by hand: in Windows PowerShell 5.1
`Remove-Item -Recurse` on a junction deletes the target's contents.

A branch with MongoDB migrations (e.g. roles) migrates its own worktree's database only.

## Stopping and cleaning up

```powershell
.\scripts\fork-uat\down.ps1                   # stop client, proxy, server, RTS; keep databases and data
.\scripts\fork-uat\down.ps1 -StopDatabases    # also stop this worktree's Redis (and MongoDB if no other worktree is up)
.\scripts\fork-uat\down.ps1 -DeleteData       # also drop this worktree's database, Redis volume and settings, and remove the client-dependency links (asks first)
```

All take `-Source <worktree>` (or `-Name`) to pick the instance.

## Setup on a new PC

- Docker Desktop, Git, Node 24.
- JDK 25: `winget install Microsoft.OpenJDK.25` (or pass any JDK 25 folder with `-JavaHome`).
- Maven 3.9 or later: `choco install maven`.

The PC's default Java can stay on another version; `up.ps1` finds the JDK 25 itself.

## Notes

- **No cloud calls needed.** Telemetry is off, and `APPSMITH_CLOUD_SERVICES_BASE_URL`
  points at a closed local port (an empty value would fall back to `cs.appsmith.com`).
  Cloud-backed features (feature flags, templates, release notes) behave as on an offline
  install. npm and Maven packages are still downloaded on the first build.
- **Encryption:** password and salt are fixed to `appsmith-dev` for every instance (local
  data only).
- **Hot reload port:** `client-dev.js` starts `app/client/scripts/start.js` with the HMR
  websocket on the instance's webpack port (`webpackDevServer.config.js` hard-codes 3000),
  without changing the worktree.
- **The dev admin takes the first-time setup.** Appsmith shows its setup page only while the
  database has no users. On an empty database `up.ps1` creates the dev admin through the
  same endpoint (`/api/v1/users/super`), so the setup page doesn't appear. Use `-NoSeed` to
  keep it. On a database that already has users, nothing is changed.
- **Memory:** the webpack dev server gets an 8 GB Node heap and used about 3 GB here; the
  server about 0.6 GB after start; RTS about 0.1 GB.

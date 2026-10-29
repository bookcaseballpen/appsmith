# About this fork

This repository is a permanent fork of [Appsmith Community Edition](https://github.com/appsmithorg/appsmith).
It is a slimmed, AI-native, internal Appsmith: it runs on its own, with no runtime dependency on anything
Appsmith hosts (no cloud services, no telemetry, no Appsmith CDN), and Git sync stays fully usable.

- **Fork point:** upstream `release` at `728ab11367`, tagged `fork-base-728ab11367`.
- **Upstream remote:** `upstream` (`https://github.com/appsmithorg/appsmith.git`). We do not follow upstream
  releases; we only cherry-pick fixes, mainly security fixes.
- **Git host:** AWS CodeCommit will be the Git host later. Git sync has no private-repo limit and does not ask
  Appsmith's cloud for anything, so it works on any host.

## Roles (phase A)

Server code: `app/server/appsmith-server/src/main/java/com/appsmith/server/roles/`.

### Fresh installs only

The roles work on a **fresh install only**. Nothing is migrated:

- On first start the server creates one workspace, "Apps", with four role groups. It stores their ids in the
  `fork.defaultWorkspace` config document and reuses them on every later start.
- Workspaces that already exist in the database are left as they are. Their CE groups and members stay, nobody
  gets a role from them, and the server logs a warning at startup.
- Old apps are re-imported one by one into "Apps" (export from the old instance, import into the new one).
- Every startup logs a line saying the fork supports fresh installs only.

### The four roles

| Role | Can |
| --- | --- |
| Admin | Everything: apps, datasources, users and roles. Instance administrator. |
| Dev | Create and edit apps, edit mode, AI and MCP, and use existing datasources in queries. Cannot create, edit, test or delete datasources (sees only name and plugin), publish, or delete apps. |
| Approver | Open the editor or preview read-only, run queries, and publish. Nothing else. |
| User | Published apps only. Never edit mode or preview, and never an app that was never published. |

- New users join "Apps" as User. Nobody gets a personal workspace.
- An Admin changes roles (`GET /api/v1/admin/users`, `PUT /api/v1/admin/users/{userId}/role`, and the "User roles"
  admin page).
- Only an Admin may create another workspace. The "Apps" workspace itself cannot be deleted.

### Publishing

- The Deploy button needs the publish permission: Approver or Admin.
- The server also publishes on its own: after creating, duplicating or forking an app, after a git commit, and after
  a git pull, merge, discard or branch checkout. For a Dev:
  - on an app that is not live yet, the publish goes ahead (Users cannot see that app) but never makes it live;
  - on a live app, the publish is skipped: the change stays in edit mode until an Approver publishes it.
  A Dev's git commit therefore still succeeds; it just does not deploy.
- Forking an app (CE "fork to workspace") also needs the datasource create permission on the target workspace,
  because it may copy datasources, so only an Admin forks. Duplicating an app within the workspace is open to Devs.
- An app is **live** once an Approver or Admin has published it manually (`lastDeployedAt` is set). Users see live
  apps only, in the app list and through every API that reads an application.
- Importing a file over a live app, or restoring a snapshot of one, needs the publish permission. Importing a new app
  is open to Devs; it is not live until it is published.

### Approver read-only filter

`ApproverReadOnlyWebFilter` refuses every non-GET `/api/**` call from an Approver except:

- `POST /api/v1/applications/publish/{id}`;
- `POST /api/v1/actions/execute`. This is on purpose: an Approver runs the unpublished app's queries in preview to
  test it before publishing, the same way Users run the queries of published apps. It runs saved queries against
  saved datasources and does not change the app;
- logout, usage pulse, and `PUT /api/v1/users` (the user's own profile).

It also refuses the GET endpoints that change state: git pull, git status, git fetch, branch listing with
`pruneRefs=true`, git commit history (it checks out a branch), deploy key generation, and the datasource OAuth
callback. It fails closed when the caller's role cannot be resolved.

## Read-only editor

Apps are designed and built in the separate projects repo; the AI exports and imports app JSON through the API and
MCP. The editor is for running, testing and debugging only, for every role, Admin included. Switch:
`APPSMITH_EDITOR_READ_ONLY` (default `true`; set `false` only in an emergency, then restart the server).

Server: `EditorReadOnlyWebFilter` (`app/server/appsmith-server/src/main/java/com/appsmith/server/editorlock/`) refuses
the editor's app-changing endpoints with a 403 and "This app is edited through the projects repo (MCP/import); the
editor is read-only.":

- every POST, PUT, PATCH and DELETE under `/api/v1/actions` (except `POST /api/v1/actions/execute`),
  `/api/v1/collections`, `/api/v1/layouts`, `/api/v1/pages`, `/api/v1/themes`, `/api/v1/libraries` and
  `POST /api/v1/import` (curl import): queries, APIs, JS objects, widgets and layouts, pages, themes, JS libraries;
- app settings: `PUT /api/v1/applications/{id}`, page order and default page (`PUT .../{id}/page/**`), the app theme
  (`PATCH .../{id}/themes/{themeId}`), navigation logo, static URL, building blocks
  (`POST /api/v1/applications/import/partial/block`) and template merge.

Still open for everyone: running queries, every GET, app import (`/api/v1/applications/import/...`, including partial
import), export, publish, git, datasources, app create, clone, fork, delete and snapshots, and user and admin
settings. The Approver filter still applies on top.

How MCP is told apart: the MCP service (`app/client/packages/mcp`) calls the server with `Authorization: Bearer mcp_...`
and the `X-Appsmith-Mcp-Internal` header carrying `APPSMITH_MCP_INTERNAL_SECRET`. Only then does
`McpTokenAuthenticationManager` authenticate the request, and it adds the `MCP_TOKEN` authority. The filter lets a
request through when its authentication has that authority. A browser session never has it, and a raw `mcp_` token
without the internal secret is not accepted at all, so no browser header can unlock the editor. MCP requests stay
confined to their allowlist (`McpAllowlistWebFilter`).

The server sends the switch to the client as the `fork_editor_read_only` feature flag (`FeatureFlagServiceImpl`,
added after the cached and remote flags). Client switch: `src/ee/utils/editorReadOnly.ts`
(`selectIsEditorReadOnly`, and a copy for the plain permission helpers kept in step by
`src/ee/sagas/editorReadOnlySagas.ts`). When it is on:

- the canvas runs as in preview (`selectCombinedPreviewMode`): no drag, drop, resize, selection, widget names or
  layout saves; the canvas-editing shortcuts (paste, delete, cut, group, undo, redo, select all) are off;
- the property pane is hidden (not read-only: the canvas has no selection to show one for);
- the UI tab and the widget explorer are hidden (`SegmentSwitcher`, `UISegmentLeftPane`); the queries and JS panes
  always open in split screen beside the running canvas (`getIDEViewMode`), and the debugger stays usable
  (`showDebuggerFlag` reads `selectCanvasOnlyMode`, which still covers preview, protected branches and Approvers);
- `ee/utils/BusinessFeatures/permissionPageHelpers.tsx` denies creating, changing and deleting pages, queries and JS
  objects, so upstream's permission UI hides "new page", "new query", "new JS" and the datasource "new query" entry
  points and makes the API, GraphQL and JS editors read-only; the DB query form (`UQIEditor/FormRender.tsx`) is
  disabled; Run buttons keep working;
- a banner says "Read-only: edit this app through the projects repo (MCP)." (`EditorReadOnlyBanner`).

The upstream integration tests run with `appsmith.editor.read-only=false` (`application-test.properties`), because
they change apps over HTTP as session users; `EditorReadOnlyWebFilterTest` and `EditorReadOnlyHttpTest` cover it on.

## Cherry-picking upstream fixes

1. Enable conflict-resolution reuse once per clone: `git config rerere.enabled true`.
2. `git fetch upstream --tags`, then find the fix commit (security advisory, release notes).
3. On a branch: `git cherry-pick -x <sha>`. The `-x` records the upstream commit in the message.
4. On conflicts, check the list of edited upstream files below first; they are the likely spots.
   If the fix touches a file the fork deleted, skip the patch and note it in "Skipped upstream patches".
5. Run the server unit tests and the client typecheck and jest suite for the touched areas.

`git diff --stat fork-base-728ab11367..HEAD` always shows everything the fork changed.

### Conventions for fork changes

- Put fork behaviour in the non-CE `*Impl` subclasses (for example `GitPrivateRepoHelperImpl`,
  `CacheableFeatureFlagHelperImpl`) or in new files. Upstream patches land in the `*CE*` classes, so they apply.
- Where an upstream file must change, keep the edit small and mark it with a `Fork:` comment.
- One logical change per commit, message prefix `fork(...)`. No repo-wide reformatting.
- Spotless: the pre-commit hook is off in this clone (`core.hooksPath` unset). Format changed server files with
  `mvn spotless:apply -DspotlessFiles=<comma-separated regexes>`, so untouched upstream files stay as they are.
- `bunfig.toml` and `bun-tests/` exist only so CCPM's merge check (`bun test` at the repo root) passes: bun is
  pointed at one smoke test instead of the jest and Cypress specs. The real suites are jest and Maven.

## Configuration

| Variable | Default | Effect |
|---|---|---|
| `APPSMITH_CLOUD_SERVICES_ENABLED` | `false` | Kill switch for cs.appsmith.com. When false, `CloudServicesConfig` returns empty base URLs and every consumer skips its call (see below). Set `true` only to restore upstream behaviour. |
| `APPSMITH_DISABLE_TELEMETRY` | `true` | Kept for compatibility; telemetry code is removed regardless. `docker.env.sh` now writes `true`. |
| `APPSMITH_EDITOR_READ_ONLY` | `true` | The read-only editor (see above): the server refuses the editor's app changes unless they come through MCP or import, and the client hides the editing UI. `false` turns both off. |

The upstream integration tests run with `appsmith.cloud_services.enabled=true`
(`app/server/appsmith-server/src/test/resources/application-test.properties`), because they expect cloud services.
The fork's disabled default is covered by unit tests of the `*Impl` classes. Test runs never reach Appsmith's cloud:
the same file sets `appsmith.cloud_services.base_url` and `signature_base_url` to `http://127.0.0.1:1`, a closed
loopback port. Unmocked calls, such as instance registration at start-up, fail locally (the log shows
"Instance registration failed ... Connection refused: /127.0.0.1:1", never "Registration successful"). Suites that need
cloud responses set their own MockWebServer URL (`ApplicationTemplateServiceTest`,
`ApplicationTemplateServicePublishTemplateTest`).

## Build and deploy

The fork builds its own image from source. Docker is the only tool needed on the build machine (no JDK, Maven,
Node or yarn on the host), and nothing is pulled from Appsmith: base layers are official Docker Hub images
(`ubuntu`, `redis`, `caddy`, `golang`, `node`, `maven` with Temurin 25) plus the MongoDB, PostgreSQL, Adoptium,
Node.js, npm, Maven Central and GitHub (mongo-tools) downloads the upstream base already used.

```sh
scripts/fork-build/build-image.sh            # Linux, macOS, Git Bash
scripts\fork-build\build-image.ps1           # Windows PowerShell
docker compose -f deploy/fork/docker-compose.yml up -d
```

1. `build-image` builds the runtime base from `deploy/docker/base.dockerfile` as `creative-app-builder-base:local`
   (upstream pulls `appsmith/base-ce:release` instead), then `deploy/fork/Dockerfile` as
   `creative-app-builder:local`. Options: image tag (first argument / `-Image`), `BASE_IMAGE` / `-BaseImage`,
   `VERSION` / `-Version` (written to `info.json`).
2. `deploy/fork/Dockerfile` is multi-stage: the client, RTS and MCP build in `node:24.14.1-bookworm`
   (`yarn install --immutable --mode=skip-build` with the committed yarn release, then the same `node` commands as
   the `build.sh` scripts, `REACT_APP_ENVIRONMENT=DEVELOPMENT` so the Faro source-map upload plugin stays off); the
   server and plugins build in `maven:3.9-eclipse-temurin-25` (`-DskipTests -Dspotless.skip=true`); the last stage
   repeats the root `Dockerfile` on top of the local base. Keep that stage in sync when a cherry-pick changes the
   root `Dockerfile`. Yarn and Maven caches are BuildKit cache mounts, so rebuilds are faster.
3. The build context is filtered by `deploy/fork/Dockerfile.dockerignore` (sources only; local `.env` files stay
   out). Line endings: `.gitattributes` keeps `*.sh`, Dockerfiles, `deploy/fork/**` and `deploy/docker/fs/**` LF in
   every new checkout. For checkouts made before that file existed (Windows, `core.autocrlf=true`), the build is
   still safe: `deploy/docker/fs` is copied with CR characters stripped, the base Dockerfile is sent to Docker on
   stdin with CRs stripped (its heredocs fail on CRLF), and `deploy/fork/Dockerfile` uses no heredocs.
4. Type checks (`tsc`, `check-types`) and `app/server/scripts/check-field-constants.mjs` are not part of the image
   build; run them as usual before merging.
5. `deploy/fork/docker-compose.yml` runs the image with its embedded MongoDB and Redis (as upstream's single image)
   and a named volume `creative-app-builder-stacks`. The port binds to `127.0.0.1:8000` by default; override with
   `APPSMITH_HTTP_PORT`, `APPSMITH_BIND_ADDRESS`, `APPSMITH_CONTAINER_NAME`, `APPSMITH_VOLUME_NAME`,
   `APPSMITH_IMAGE`. `pull_policy: never`, so compose never tries a registry. On first start the container writes
   `stacks/configuration/docker.env` with generated MongoDB, Redis and encryption secrets: keep the volume.
6. Health: `docker inspect --format '{{.State.Health.Status}}' creative-app-builder` (the image's healthcheck), or
   `http://127.0.0.1:8000/api/v1/health`. The healthcheck turns `healthy` about a minute before the backend is up:
   until then the editor shows "Appsmith is starting" and `/api/v1/health` answers 502. Wait for the 200.
7. Build time, measured on an 8-CPU, 20 GB Docker Desktop with empty caches: about 45 minutes (6 minutes to send
   the ~500 MB build context the first time, then `yarn install` 18 minutes and the client build 19 minutes, with
   Maven's 20 minutes running alongside). With warm caches, a change to one part rebuilds only that part; a
   Dockerfile-only change in the last stages takes about a minute.

`scripts/local_testing.sh [tag] [port]` builds the current working tree with `build-image.sh` and runs it as
`creative-app-builder-local` on `127.0.0.1:80`; upstream's branch and remote modes (git fetch, checkout and pull) are
removed. `deploy/docker/docker-compose.yml` uses `creative-app-builder:local`. The upstream root `Dockerfile` (with a
fork comment pointing here; keep its steps and `deploy/fork/Dockerfile`'s final stages in sync) still works with
`--build-arg BASE=creative-app-builder-base:local` after host builds of the artifacts, but needs JDK 25, Maven and
yarn on the host and LF line endings in `deploy/docker/fs`.

The Watchtower labels of the root `Dockerfile` are not set on the fork image: Watchtower updates from a registry,
and the fork image is local. The hook scripts stay in the image (`/watchtower-hooks`), unused.

## Feature flags (local defaults)

Flag values normally come from cs.appsmith.com. With cloud services disabled they come from
`app/server/appsmith-server/src/main/java/com/appsmith/server/featureflags/ForkFeatureFlagDefaults.java`, for both
user and organization flags, and the server sends them to the client (`/api/v1/users/features`), where they are
merged over the client defaults in `app/client/src/ce/entities/FeatureFlag.ts`. Any flag not listed is `false`,
as upstream when the cloud is unreachable.

| Flag | Value | Why |
|---|---|---|
| `license_git_branch_protection_enabled` | true | Shows the protected-branch settings. The server API is not licence-gated, so "protect main" works. |
| `release_git_autocommit_feature_enabled` | true | Deprecated in CE (auto-commit is enabled per app in its Git settings); true so nothing that reads it blocks Git. |
| `release_git_autocommit_eligibility_enabled` | true | Same as above. |
| `license_git_continuous_delivery_enabled` | false | Enterprise feature, no CE server support. |
| `release_git_modularisation_enabled` | false | Keeps the legacy Git UI (`pages/Editor/gitSync`), the one upstream CE runs by default. |
| `release_git_api_contracts_enabled` | false | Keeps the Git API contracts the legacy UI uses. |
| `release_git_persist_branch_enabled` | false | Upstream default. |
| `release_git_reset_optimization_enabled` | false | Upstream default when the cloud is unreachable; the plain reset path. |
| `ab_rts_git_reset_enabled` | false | Same as above. |
| `rollout_datasource_test_rate_limit_enabled` | true | Rate-limits "test datasource" calls (brute-force protection). |
| `release_alphabetical_ordering_enabled` | true | Workspaces and apps listed alphabetically. |
| `rollout_remove_feature_walkthrough_enabled` | true | No feature walkthroughs (upstream client default). |
| `kill_session_recordings_enabled` | true | No session recordings (the recorders are removed anyway). |
| `config_user_session_recordings_enabled` | false | Same as above. |

To change a flag, edit `ForkFeatureFlagDefaults` and this table together.

`fork_editor_read_only` is not in that list: `FeatureFlagServiceImpl` sets it from `APPSMITH_EDITOR_READ_ONLY` on every
request, whatever the cache or the cloud says (see "Read-only editor").

## What the fork turns off, removes or stubs

### Appsmith cloud services (cs.appsmith.com)

Off by default through `APPSMITH_CLOUD_SERVICES_ENABLED`; no request is made and nothing retries.

| Caller | Behaviour when disabled | Where |
|---|---|---|
| Feature flags (user and organization) | Local defaults (above) | `CacheableFeatureFlagHelperImpl` |
| Instance registration at start-up | Skipped | `InstanceConfigHelperImpl` |
| External-IP lookup | Returns `unknown` | `NetworkUtils` |
| Release notes / "what's new" / new-version check | Empty base URL, CE code skips; the list is empty | `ReleaseNotesUtilsCEImpl` (unchanged) |
| Plugin catalogue sync (every 2 h) | Empty base URL, CE code skips; plugins stay as in the database | `PluginScheduledTaskUtilsCEImpl` (unchanged) |
| Templates (list, filters, similar) | Empty (filters as empty lists). The client requests the filters once and treats an empty result or an error as final (`shouldFetchTemplateFiltersSelector`); it used to refetch without end. | `ApplicationTemplateServiceImpl` |
| Template details, fork, merge, building blocks, publish | Cloud-services error | `ApplicationTemplateServiceImpl` |
| Sample databases (mock data) | Empty list; creating one is a cloud-services error | `MockDataServiceImpl` |
| Upcoming integrations list | Empty | `PluginServiceImpl` |
| Git private-repo limit | No limit (see Git) | `GitPrivateRepoHelperImpl`, `GitCloudServicesUtils` |
| Pylon email hash | Never requested | `CacheablePylonHelperImpl` |
| SaaS plugin OAuth (Google Sheets and other proxied OAuth) | Authorizing fails with a cloud-services error, and so does the `/api/v1/saas/authorize` redirect. Token refresh for those plugins fails locally on the empty URL; generic OAuth2 for REST APIs is unaffected. | `AuthenticationServiceImpl`, `SaasController` |
| SaaS plugin remote execution (`/api/v1/actions/execute`) | Cloud-services error when used (the URL getter throws instead of returning a relative path) | `SharedConfigImpl` |
| Appsmith AI plugin (Appsmith-hosted AI proxy) | Cloud-services error when used. The plugin now reads the proxy URL on first use, not at load, so start-up is unaffected. Upstream already blocks creating new Appsmith AI datasources; the OpenAI, Anthropic and Google AI plugins call the provider configured locally. | `SharedConfigImpl`, `AppsmithAiPlugin` |

**Proxied OAuth is disabled.** Google Sheets (and any SaaS plugin that authorizes through Appsmith's cloud)
cannot be connected. Use a REST API datasource with your own OAuth2 app instead.

### Telemetry, support chat and usage tracking

| Item | What happened |
|---|---|
| Server Segment (events, identify, daily "Instance Active" and `instance_stats` pings) | Dependency removed. `AnalyticsServiceCEImpl` is an inert shim (many call sites), `SegmentConfig` keeps an empty CE key, `ScheduledTaskImpl` makes both pings no-ops. |
| Server Sentry | Dependency and properties removed. |
| Usage pulse | Client module deleted; server `UsagePulseServiceImpl.createPulse` records nothing. |
| Client Segment, Mixpanel (session recording), Smartlook | Wrappers and npm packages deleted. `AnalyticsUtil` is an inert shim. |
| Client Sentry | Redux enhancer, `captureException` and `ErrorBoundary` usages replaced (loglevel, our own `ErrorBoundry`); packages deleted. `SentryRoute` is only an alias of `Route` and stays. |
| BetterBugs | SDK wrapper, recording-link scripts in `index.html`, "Send support info" menu items and the npm package deleted. |
| Pylon chat (and the legacy Intercom settings) | Loader in `index.html` and the SDK script injection deleted; `bootPylon.ts` is a shim that reports the chat as unavailable, so every `window.Pylon(...)` call is unreachable. |
| Reo tracker | Deleted from `index.html`. |
| `404.html` | Smartlook and Sentry loaders deleted. |
| Docker | `Dockerfile` bakes no cloud-services URL, Segment, BetterBugs or Pylon keys. `entrypoint.sh` drops the "Instance Start" ping. `docker.env.sh` writes `APPSMITH_DISABLE_TELEMETRY=true` and `APPSMITH_CLOUD_SERVICES_ENABLED=false`. |

Left on purpose: Grafana Faro / OpenTelemetry tracing (`app/client/src/instrumentation`) only runs when an operator
sets `APPSMITH_FRONTEND_TRACING_URL`, which has no default; it then sends to that operator's own collector.

### Appsmith CDN assets

Runtime assets used to load from `assets.appsmith.com` (plugin icons stored in the database, empty states,
widget defaults, login and onboarding images). They are now committed under `app/client/public/assets-cdn/`
(57 files, about 6 MB) and served by the client build:

- `ASSETS_CDN_URL` is `/assets-cdn` (`app/client/src/constants/ThirdPartyConstants.tsx`).
- `getAssetUrl()` (`app/client/src/ce/utils/airgapHelpers.tsx`) rewrites any absolute `assets.appsmith.com` URL,
  for example plugin icons from the database, to `/assets-cdn/...`. The Image, Video and Audio widgets apply it
  when rendering, so apps saved with the upstream default URLs (including pages generated from
  `CRUD-DB-Table-Template-Application.json`) load the local copy.
- `loading.html` and the MCP builder's default image point at `/assets-cdn`.
- Email templates no longer show the Appsmith logo (it was an image on the CDN; mail clients would fetch it).
- Not vendored: the nine Git "how to" GIFs (about 70 MB). Their demo sections in the Git connect flow are hidden:
  `GIT_DEMO_GIF` holds empty strings, and all six render sites (three in `src/git/components`, three in the older
  `pages/Editor/gitSync/Tabs/GitConnectionV2`) render the section only when the GIF URL is truthy, so no
  `<img src="">` is produced. Tests cover `AddDeployKey`, `ChooseGitProvider` and `GenerateSSH`.

After a cherry-pick that adds an asset, run `node app/client/scripts/fork-vendor-assets.js --dry` from the repo
root; without `--dry` it downloads what is missing into `public/assets-cdn`. Commit the new files.

### Git without the cloud

- `GitPrivateRepoHelperImpl.isRepoLimitReached` always returns false, so connect, import and commit work for
  private repos on any host, with no limit.
- `GitCloudServicesUtils` returns "no limit" without a call when cloud services are disabled.
- Auto-commit of DSL migrations is controlled per app (`GitUtils.isAutoCommitEnabled`), not by a cloud flag.
- `GitUtils.isRepoPrivate` still sends an unauthenticated HTTPS request to the Git host's browser URL to classify
  a repo. That is the Git host (later CodeCommit), not Appsmith.

## Still referencing Appsmith hosts (left on purpose)

- **Static links** (opened by the user, nothing loads them at runtime): about 290 `docs.appsmith.com` links in
  the client and server error messages, `docs.appsmithai.com`, `community.appsmith.com`,
  `www.appsmith.com/enterprise`, the "Built on Appsmith" badge link (`appsmith.com`), Discord and GitHub release
  links. To be replaced or removed in a later task.
- **Upgrade links:** `index.html` no longer defaults `customerPortalUrl` to `customer.appsmith.com` or `pricingUrl`
  to `www.appsmith.com/pricing`; both are empty unless `APPSMITH_CUSTOMER_PORTAL_URL` / `APPSMITH_PRICING_URL` are
  set. `PRICING_PAGE_URL` and `CUSTOMER_PORTAL_URL_WITH_PARAMS` then return `""` instead of a relative
  `?source=...` link, and `useOnUpgrade` opens nothing. The upgrade buttons and ramps themselves are still shown;
  hiding them is left for a later task.
- **Placeholders and hints, never fetched:** `mock-api.appsmith.com/users` as an input placeholder, the
  `example` URLs in `packages/dsl/.../widget-configs.json`, the `release.app.appsmith.com` strings inside
  `CRUD-DB-Table-Template-Application.json`, `DEFAULT_ORIGIN_HEADER` (`app.appsmith.com`, a redirect fallback
  when a request has no Origin), `ai.appsmith.com` as the old Appsmith AI datasource URL (migration 047 and the
  plugin form) and the `HostUrlHelperCE` Javadoc examples.
- **Test fixtures and Storybook stories:** `app.appsmith.com`, `appcdn.appsmith.com`, `dev.appsmith.com` and
  `assets.appsmith.com` URLs in unit-test data and design-system stories.
- `CloudServicesConfigCE` still contains the upstream `https://cs.appsmith.com` default; it is only used when the
  kill switch is turned on.
- `ScheduledTaskCEImpl` still contains the `api.segment.io` ping code; `ScheduledTaskImpl` overrides both scheduled
  methods with no-ops, so it never runs.
- Names only, no SDK behind them: `SentryRoute` (an alias of `Route`), `logToSentry` / `getLogToSentryFromResponse`
  flags in sagas (they route errors to `appsmithTelemetry.captureException`, which only reports to the operator's
  own Faro collector, see above), the `smartlook.cloud` entry in the
  tracing ignore list, the `data-sl` canvas attribute, the `window.Sentry` / `window.Pylon` type declarations and
  comments mentioning Mixpanel or Segment.
- Every `window.Pylon(...)` call sits behind `isPylonChatAvailable()` (directly or through the menu item's
  visibility), which the shim makes false.
- `@sentry/browser` in `recommendedLibraries.ts` is a library a user can choose to install into an app; nothing
  loads it by default.
- Hint strings: the image-URL example in `utils/validation/common.ts` (`app.appsmith.com/static/...`) and the
  "request a template" link in `pages/Templates/Template/RequestTemplate.tsx` (an Appsmith-hosted form; templates
  are empty with the cloud off, so it is not reachable from the normal UI).
- Third-party, not Appsmith: Google APIs for the Google Sheets picker (`sagas/loadGoogleApi.ts`), Google Fonts on
  the static `terms-and-conditions.html`, CDNs (jsdelivr, unpkg, cdnjs) used by the custom JS library installer.

## Build and deploy: what still references Appsmith

Cleaned in the standalone-build task: the Appsmith base image (`appsmith/base-ce`) is replaced by the locally built
base; `scripts/local_testing.sh` no longer passes a `release-cs.appsmith.com` URL or pulls the Appsmith base;
`.env.example` documents `APPSMITH_CLOUD_SERVICES_ENABLED` instead of `release-cs.appsmith.com`;
`scripts/deploy_preview.sh` and `scripts/cleanup_dp.sh` (Appsmith's own EKS cluster, `helm-ee.appsmith.com`,
`*.dp.appsmith.com`) and `deploy/docker/fs/opt/appsmith/pull_resource.sh` (an AMI helper that downloaded a compose
file from `raw.githubusercontent.com/appsmithorg`, called by nothing) are deleted; `deploy/docker/docker-compose.yml`
and `deploy/docker/route-tests/Dockerfile` use the local images.

Audit of the image itself (`deploy/docker/`): no script in the image calls an Appsmith host at build time or at
run time. Remaining mentions are text only: `docs.appsmith.com` links in log messages (`entrypoint.sh`,
`install_docker.sh`, RTS `ctl/mailer.ts`), `support@appsmith.com`, the `LABEL maintainer` of `base.dockerfile`,
GitHub issue links in comments, and the plugin `iconLocation` URLs in `baseline-ce/plugin.jsonl` (the client
rewrites them to `/assets-cdn`). Third-party downloads that can happen at run time: `entrypoint.sh` compiles Redis
from `download.redis.io` only when the host page size is above 4 KB (for example some ARM kernels);
`install_docker.sh` (not called by the image) uses `download.docker.com` and GitHub.

Checked at run time on `creative-app-builder:local` (October 2026), with a `tcpdump` container sharing the app
container's network namespace: a first boot on an empty volume (MongoDB init, migrations), the first-admin signup,
the login page and its APIs, logged-in API calls, a restart of backend, RTS and MCP, and about 15 minutes of idle
running. The capture held only the HTTP requests from the host on port 80: no DNS query and no outbound connection
of any kind. The container logs have no Appsmith host (the only URLs are `localhost`, `slf4j.org` and a Micrometer
GitHub issue in startup warnings).

The built image filesystem (`/opt/appsmith`, source maps excluded) still has these Appsmith URLs; none is fetched by
the server, RTS or the loaded editor:

- `docs.appsmith.com` (about 270 occurrences), `appsmith.com`, `www.appsmith.com/enterprise`, `www.appsmith.com/blog`,
  `community.appsmith.com` and the request-a-template form on `app.appsmith.com`: links, see the list above.
- `assets.appsmith.com/widgets/bird.mp4` and `birds_chirping.mp3` in the widget configs: `example` strings in
  property validation hints (the widgets' defaults use the local `/assets-cdn`), and the plugin `iconLocation` URLs
  in `baseline-ce/plugin.jsonl`, rewritten by the client.
- `mock-api.appsmith.com/users` and `app.appsmith.com/static/media/...png`: a placeholder and a hint.
- `CRUD-DB-Table-Template-Application.json` in the server jar: besides the `release.app.appsmith.com` strings, two
  widgets have `defaultImage: https://assets.appsmith.com/widgets/default.png`. If "Generate page from data" copies
  one of those widgets into an app, the browser loads that image from Appsmith. To fix in a later task (point it at
  `/assets-cdn/widgets/default.png`, as the MCP template already does).
- `DEFAULT_ORIGIN_HEADER` (`https://app.appsmith.com`): the server also uses it as the redirect target when a
  first-admin or user signup fails and the request has no `Referer`. A browser always sends one, so only
  hand-written requests (curl) end up redirected to Appsmith. To replace in a later task.

Left as they are (not used by the fork's build or deploy):

- `deploy/aws`, `aws_ami`, `ansible`, `digital_ocean`, `heroku`, `helm`, `packer`: upstream's deploy targets. They pull
  `appsmith/appsmith-ce` / `-ee` images, the `helm.appsmith.com` chart repository or files from
  `raw.githubusercontent.com/appsmithorg`. Do not use them; remove or port in a later task.
- `deploy/docker/README.md`, `build-multi-arch.md`, `build-mutil-arch.sh` (pushes `appsmith/appsmith-ce`),
  `deploy/docker/tests/composes.sh` and `tests/pg-upgrade/run.sh` (pull published `appsmith/appsmith-ce` tags to
  test upgrades), `deploy/docker/run-test.sh` (an `example@appsmith.com` test login).
- `app/client/download-assets.js` (airgap builds only) downloads from `assets.appsmith.com`; not used by the fork's
  build, since the assets are committed.
- `@grafana/faro-webpack-plugin` uploads source maps to Grafana when `REACT_APP_ENVIRONMENT=PRODUCTION`; the fork
  build uses `DEVELOPMENT`.
- Cypress scripts in `app/client/package.json` use `CYPRESS_BASE_URL=https://dev.appsmith.com`;
  `app/client/start-https.sh` and `start-caddy.sh` default the dev proxy backend to `release.app.appsmith.com`
  (pass your own backend URL).

### GitHub Actions workflows (deleted)

All upstream workflows were deleted. We check locally (typecheck, tests). Any future workflow must set
timeout-minutes (default 10), concurrency with cancel-in-progress, and prefer workflow_dispatch.

Deleted with them: everything under `.github/workflows/` (workflows, their scripts and docs), the composite action
`.github/actions/setup-server-java`, `.github/dependabot.yml`, and the configs only those workflows or upstream's
bots read (`labeler.yml`, `release-drafter-template.yml`, `reviewers.yml`, `config.json`, `config-test.json`).
Kept: the issue and PR templates, and `semantic.yml` / `config.yml` (settings for the `semantic-prs` and welcome
GitHub Apps; they do nothing unless those apps are installed on the fork, and use no Actions minutes). Some docs
still mention deleted workflows (`contributions/docs/TestAutomation.md`, `deploy/helm/AGENTS.md`, `.cursor/rules`).

## Skipped upstream patches

None yet.

## Upstream files this fork edits

Kept up to date with `git diff --name-status fork-base-728ab11367..HEAD`. `A` = new fork file, `D` = deleted
upstream file, `M` = edited upstream file.

### Edited upstream files (`M`)

Repo root and deploy:

- `.cursor/rules/infra.mdc`, `.env.example`
- `Dockerfile`
- `deploy/docker/docker-compose.yml`, `deploy/docker/route-tests/Dockerfile`
- `scripts/local_testing.sh`
- `deploy/docker/fs/opt/appsmith/entrypoint.sh`
- `deploy/docker/fs/opt/appsmith/templates/docker.env.sh`
- `deploy/docker/fs/opt/appsmith/templates/loading.html`
- `deploy/docker/tests/composes.sh`

Client (`app/client/`):

- `.env.example`, `build.sh`, `config/webpack.config.js`, `package.json`, `yarn.lock`
- `packages/mcp/src/builder/templates.ts`
- `public/404.html`, `public/index.html`
- `src/PluginActionEditor/components/PluginActionResponse/components/Visualization/Visualization.tsx`
- `src/api/interceptors/request/addAnonymousUserIdHeader.ts`
- `src/api/interceptors/request/apiRequestInterceptor.ts`
- `src/ce/configs/index.ts`, `src/ce/configs/index.test.ts`, `src/ce/configs/types.ts`
- `src/ce/pages/Applications/CreateNewAppFromTemplateModal/index.tsx`
- `src/components/editorComponents/PartialImportExport/PartialExportModal/unitTestUtils.ts`
- `src/ce/sagas/NavigationSagas.ts`, `src/ce/sagas/userSagas.tsx`
- `src/ce/utils/Analytics/trackedUser.ts`
- `src/ce/utils/AnalyticsUtil.tsx`, `src/ce/utils/AnalyticsUtil.test.ts`
- `src/ce/utils/airgapHelpers.tsx`
- `src/constants/ThirdPartyConstants.tsx`
- `src/entities/Replay/index.ts`
- `src/git/components/ConnectModal/ConnectInitialize/ChooseGitProvider.tsx` (and `.test.tsx`)
- `src/git/components/ConnectModal/ConnectInitialize/GenerateSSH.tsx` (and `.test.tsx`)
- `src/git/components/common/AddDeployKey.tsx`, `src/git/components/common/constants.ts`
- `src/pages/AdminSettings/index.tsx`
- `src/pages/Editor/AppsmithLink.tsx`
- `src/pages/Editor/HelpButton.tsx`, `src/pages/Editor/__tests__/HelpButton.test.tsx`
- `src/pages/Editor/gitSync/Tabs/GitConnectionV2/AddDeployKey.tsx`, `ChooseGitProvider.tsx`, `GenerateSSH.tsx`,
  `constants.ts`
- `src/pages/Templates/index.tsx`, `src/pages/Templates/TemplatesModal/index.tsx`
- `src/reducers/uiReducers/templateReducer.ts`, `src/selectors/templatesSelectors.tsx`
- `src/pages/common/SearchBar/HomepageHeaderAction.tsx` (and `__tests__/HomepageHeaderAction.test.tsx`)
- `src/store.ts`
- `src/utils/bootPylon.ts`, `src/utils/hooks/useOnUpgrade.ts`
- `src/widgets/AudioWidget/widget/index.tsx`, `src/widgets/ImageWidget/widget/index.tsx`,
  `src/widgets/VideoWidget/widget/index.tsx`
- `src/workers/Evaluation/ReplayDSL.ts`, `src/workers/Evaluation/fns/overrides/console.ts`

Server (`app/server/appsmith-server/`):

- `pom.xml`
- `src/main/java/com/appsmith/server/configurations/CloudServicesConfig.java`, `SegmentConfig.java`,
  `SharedConfigImpl.java`
- `src/main/java/com/appsmith/server/controllers/SaasController.java`
- `src/main/java/com/appsmith/server/helpers/GitCloudServicesUtils.java`, `GitPrivateRepoHelperImpl.java`,
  `InstanceConfigHelperImpl.java`, `NetworkUtils.java`
- `src/main/java/com/appsmith/server/plugins/base/PluginServiceImpl.java`
- `src/main/java/com/appsmith/server/services/AnalyticsServiceImpl.java`, `ApplicationTemplateServiceImpl.java`,
  `CacheableFeatureFlagHelperImpl.java`, `CacheablePylonHelperImpl.java`, `MockDataServiceImpl.java`,
  `UsagePulseServiceImpl.java`
- `src/main/java/com/appsmith/server/services/ce/AnalyticsServiceCEImpl.java` (the one `CE` class the fork
  rewrites: the Segment client it wrapped is gone)
- `src/main/java/com/appsmith/server/solutions/AuthenticationServiceImpl.java`, `ScheduledTaskImpl.java`
- `src/main/resources/application-ce.properties`, `application-ee.properties`
- `src/main/resources/email/ce/emailVerificationTemplate.html`, `forgotPasswordTemplate.html`,
  `instanceAdminInviteTemplate.html`, `inviteWorkspaceExistingUserTemplate.html`,
  `inviteWorkspaceNewUserTemplate.html`
- `src/test/java/com/appsmith/server/git/ops/GitConnectTests.java`
- `src/test/java/com/appsmith/server/helpers/ce/GitPrivateRepoCEImplTest.java`
- `src/test/resources/application-test.properties`

Server plugins (`app/server/appsmith-plugins/`):

- `appsmithAiPlugin/src/main/java/com/external/plugins/AppsmithAiPlugin.java`

### Deleted upstream files (`D`)

- Scripts: `scripts/deploy_preview.sh`, `scripts/cleanup_dp.sh`, `deploy/docker/fs/opt/appsmith/pull_resource.sh`
- GitHub automation: all of `.github/workflows/`, `.github/actions/`, `.github/dependabot.yml`, `labeler.yml`,
  `release-drafter-template.yml`, `reviewers.yml`, `config.json`, `config-test.json` (see "GitHub Actions workflows")
- Client: `src/ce/constants/UsagePulse.ts`, `src/ee/constants/UsagePulse.ts`, `src/ce/hooks/useBetterbugsMetadata.ts`,
  `src/ee/hooks/useBetterbugsMetadata.ts`, `src/ce/hooks/__tests__/useBetterbugsMetadata.test.tsx`,
  `src/ce/sagas/userSagas.test.ts`, `src/usagePulse/` (`index.ts`, `utils.ts` and their tests),
  `src/utils/Analytics/betterbugs.ts`, `mixpanel.ts`, `segment.ts`, `smartlook.ts`, `segment.test.ts`,
  `__tests__/betterbugs.test.ts`
- Server tests: `services/UsagePulseServiceTest.java`, `services/ce/AnalyticsServiceCEImplTest.java`

### New fork files (`A`)

- `FORK.md`, `.gitattributes`
- `deploy/fork/` (`Dockerfile`, `Dockerfile.dockerignore`, `docker-compose.yml`)
- `scripts/fork-build/` (`build-image.sh`, `build-image.ps1`)
- `app/client/public/assets-cdn/` (vendored assets)
- `app/client/scripts/fork-vendor-assets.js`
- `app/client/src/ce/utils/airgapHelpers.test.ts`, `app/client/src/constants/ThirdPartyConstants.test.ts`,
  `app/client/src/git/components/common/AddDeployKey.test.tsx`,
  `app/client/src/ce/pages/Applications/CreateNewAppFromTemplateModal/index.test.tsx`
- `app/server/appsmith-server/src/main/java/com/appsmith/server/featureflags/ForkFeatureFlagDefaults.java`
- `app/server/appsmith-plugins/appsmithAiPlugin/src/test/java/com/external/plugins/AppsmithAiPluginExecutorTest.java`
- Server unit tests: `configurations/CloudServicesConfigTest`, `configurations/SharedConfigImplTest`,
  `controllers/SaasControllerTest`, `helpers/GitCloudServicesUtilsTest`,
  `helpers/GitPrivateRepoHelperImplTest`, `helpers/InstanceConfigHelperImplTest`, `helpers/NetworkUtilsTest`,
  `services/AnalyticsServiceImplTest`, `services/ApplicationTemplateServiceImplTest`,
  `services/CacheableFeatureFlagHelperImplTest`, `services/CacheablePylonHelperImplTest`,
  `services/MockDataServiceImplTest`, `services/UsagePulseServiceImplTest`
- Roles (phase A): `app/server/appsmith-server/src/main/java/com/appsmith/server/roles/`, the server tests
  `acl/PolicyGeneratorTest`, `configurations/SeedTestUserRoles`, `roles/*Test`,
  `solutions/DatasourcePermissionImplTest`, and the client files `src/ce/api/InstanceRoleApi.ts`,
  `src/ee/api/InstanceRoleApi.ts`, `src/ce/utils/instanceRoles.ts` (and `.test.ts`), `src/ee/utils/instanceRoles.ts`,
  `src/ce/pages/AdminSettings/config/roles.ts`, `src/pages/AdminSettings/Roles/index.tsx`,
  `src/pages/AppIDE/RequireEditRole.tsx` (and `.test.tsx`),
  `src/ee/utils/BusinessFeatures/permissionPageHelpers.tsx` (CE stub, now overrides the datasource checks; and
  `.test.ts`)

### Upstream files edited for roles (phase A)

- Client: `src/ce/AppRouter.tsx`, `src/ce/pages/AdminSettings/config/index.ts`, `src/selectors/gitModSelectors.ts`,
  `src/pages/AppIDE/layouts/components/Header/DeployButton.tsx` (disabled for Devs),
  `src/pages/AppIDE/layouts/hooks/useEditorStateLeftPaneWidth.ts` and `useGridLayoutTemplate.ts` (Approvers get the
  canvas only), `src/pages/Editor/DataSourceEditor/DSFormHeader.tsx` (Edit only with manage),
  `src/sagas/InitSagas.ts` (an editor URL opens view mode for Users) and `src/sagas/__tests__/initSagas.test.ts`
- Server main (`src/main/java/com/appsmith/server/`): `acl/PolicyGenerator.java`,
  `datasources/base/DatasourceServiceImpl.java`, `dtos/UserProfileDTO.java`, `helpers/UserUtils.java`,
  `helpers/WorkspaceServiceHelperImpl.java`, `imports/internal/ImportServiceImpl.java`,
  `repositories/CustomApplicationRepositoryImpl.java`, `services/AIAssistantServiceImpl.java`,
  `services/ApplicationPageServiceImpl.java`, `services/McpTokenServiceImpl.java`, `services/UserServiceImpl.java`,
  `services/WorkspaceServiceImpl.java`, `solutions/ApplicationPermissionImpl.java`,
  `solutions/DatasourcePermissionImpl.java`
- Server tests (`src/test/java/com/appsmith/server/`): `searchentities/SearchEntitySolutionCETest.java`,
  `services/DatasourceStorageServiceTest.java`, `services/McpTokenServiceImplTest.java`,
  `services/UserWorkspaceServiceTest.java`, `services/WorkspaceServiceTest.java`

### Read-only editor files

- New, server: `editorlock/EditorReadOnlySetting.java`, `editorlock/EditorReadOnlyWebFilter.java` and the tests
  `editorlock/EditorReadOnlyWebFilterTest`, `editorlock/EditorReadOnlyHttpTest`.
- New, client: `src/ee/utils/editorReadOnly.ts` (and `.test.ts`), `src/ee/sagas/editorReadOnlySagas.ts`,
  `src/pages/AppIDE/components/EditorReadOnlyBanner/` (and test),
  `src/pages/AppIDE/layouts/components/SegmentSwitcher/SegmentSwitcher.test.tsx`.
- Edited, server: `services/FeatureFlagServiceImpl.java`, `src/main/resources/application-ce.properties`,
  `src/test/resources/application-test.properties`.
- Edited, client: `src/ce/entities/FeatureFlag.ts`, `src/ee/sagas/index.tsx`,
  `src/ee/utils/BusinessFeatures/permissionPageHelpers.tsx` (and `.test.ts`), `src/selectors/gitModSelectors.ts`,
  `src/selectors/debuggerSelectors.tsx`, `src/selectors/ideSelectors.tsx`,
  `src/pages/AppIDE/layouts/hooks/useEditorStateLeftPaneWidth.ts` and `useGridLayoutTemplate.ts`,
  `src/pages/AppIDE/layouts/AnimatedLayout.tsx` and `StaticLayout.tsx`,
  `src/pages/AppIDE/layouts/components/SegmentSwitcher/SegmentSwitcher.tsx`,
  `src/pages/AppIDE/layouts/components/EditorTabs/ScreenModeToggle.tsx`,
  `src/pages/AppIDE/layouts/routers/UISegmentLeftPane/UISegmentLeftPane.tsx`,
  `src/pages/Editor/commons/EditorWrapperContainer.tsx`, `src/pages/Editor/GlobalHotKeys/GlobalHotKeys.tsx`,
  `src/PluginActionEditor/components/PluginActionForm/components/UQIEditor/FormRender.tsx`,
  `src/ee/sagas/ApiCallerSagas.ts` (and `.test.ts`: when read-only, the JS-object and action syncs the
  client sends on load stay in the client instead of hitting the server guard),
  `CommonEditorForm/InfoFields.tsx` and `components/EmbeddedDatasourcePathField.tsx` (the API URL field
  follows the action's edit permission and hides "Save URL" when disabled)

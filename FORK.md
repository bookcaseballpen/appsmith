# About this fork

This repository is a permanent fork of [Appsmith Community Edition](https://github.com/appsmithorg/appsmith).
It is a slimmed, AI-native, internal Appsmith: it runs on its own, with no runtime dependency on anything
Appsmith hosts (no cloud services, no telemetry, no Appsmith CDN). Apps are versioned outside Appsmith (export and
import, by the AI, in a separate repository), so Appsmith's built-in Git is off by default; its code still works when
switched on (see "Hidden features").

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

- Every publish goes through the release approval flow (see "Release approvals"): nobody deploys directly, Admins
  and Approvers included. `POST /api/v1/applications/publish/{id}` is refused for every role (403); only approve
  publishes, by calling the publish service inside `PublishApproval.asApprovedRelease` (a Reactor context marker no
  request can set). The editor has no Deploy button.
- The server also publishes on its own: after creating, duplicating or forking an app, after a git commit, and after
  a git pull, merge, discard or branch checkout. For every signed-in role:
  - on an app that is not live yet, the publish goes ahead (Users cannot see that app) but never makes it live;
  - on a live app, the publish is skipped: the change stays in edit mode until a release of it is approved.
  A git commit therefore still succeeds; it just does not deploy. Publishes without a signed-in user (the server's
  own startup work) are not limited; the fork's startup and seed code does not call the publish endpoint.
- Forking an app (CE "fork to workspace") also needs the datasource create permission on the target workspace,
  because it may copy datasources, so only an Admin forks. Duplicating an app within the workspace is open to Devs.
- An app is **live** once an Approver or Admin has published it manually (`lastDeployedAt` is set). Users see live
  apps only, in the app list and through every API that reads an application.
- Importing a file over an existing app replaces its **edit copy only** (`ImportServiceImpl`): the file's published
  side is dropped before the import (`projectsrepo/EditCopyJson.dropPublishedSide`), and the importers keep the stored
  published copy of every page, query and JS object, the published app settings and theme, and `lastDeployedAt`. So
  Devs (and MCP) may import over a live app, and its users keep seeing the approved version; a new version goes live
  only through Submit -> Approve. A pending request is cancelled by the fingerprint rule, since the edit copy changed.
  Over a live app the view-mode fingerprint is compared before and after the import; should the import have changed
  what users see anyway, the app is taken offline (`PublishApproval.markNotLive`), as approve does.
- Restoring a snapshot of a live app still needs the publish permission and takes the app offline, because the
  snapshot's view mode was not approved; it is live again once a release is approved. Importing a new app is open to
  Devs; it is not live until a release is approved.

### Approver read-only filter

`ApproverReadOnlyWebFilter` refuses every non-GET `/api/**` call from an Approver except:

- submit, approve and reject a release (`POST /api/v1/approvals/applications/{id}/submit|approve|reject`);
- `POST /api/v1/actions/execute`. This is on purpose: an Approver runs the unpublished app's queries in preview to
  test it before publishing, the same way Users run the queries of published apps. It runs saved queries against
  saved datasources and does not change the app;
- logout, usage pulse, and `PUT /api/v1/users` (the user's own profile).

It also refuses the GET endpoints that change state: git pull, git status, git fetch, branch listing with
`pruneRefs=true`, git commit history (it checks out a branch), deploy key generation, and the datasource OAuth
callback. It fails closed when the caller's role cannot be resolved.

## Release approvals

Server code: `app/server/appsmith-server/src/main/java/com/appsmith/server/approvals/` and the domain
`domains/ApplicationApproval.java` (collection `applicationApproval`, one document per application or git branch, its
id is the application id). Client: `src/ee/api/ApprovalApi.ts`, `src/ee/utils/approvals.ts`,
`src/ee/components/approvals/`, `src/ee/pages/AppIDE/layouts/components/Header/ReleaseControls.tsx` and
`src/ee/pages/Applications/PendingApprovals.tsx`. Notifications are in-app only (no email).

### The flow

| Status | Means |
| --- | --- |
| Draft | Nothing submitted, a request was cancelled, or the approved version has changed since. |
| Submitted ("Waiting for approval") | A Dev, Admin or Approver submitted the current edit-mode version. |
| Approving ("Being published") | An Approver or Admin approved it and the publish is running (the claim, see below). |
| Approved | An Approver or Admin approved it; it is published and live. |
| Rejected | An Approver or Admin rejected it with a comment; the Dev sees the comment until the next submit. |

- **Editor header (every role that opens the editor):** "Submit for approval" and a status chip where upstream has
  Deploy. Clicking the chip shows the reject comment, why a request was cancelled, and the last history entries.
- **Approver / Admin:** the editor header also shows Approve while a release is waiting. The home page shows "Waiting
  for approval" with a count badge, and per app Preview (the editor, which opens read-only for Approvers), Approve and
  Reject (a comment is required). An Admin or Approver may approve their own submit.
- The app keeps a history of the last 20 events (submitted, approved, rejected, cancelled): who, when and the comment.

| Endpoint | Who |
| --- | --- |
| `GET /api/v1/approvals/applications/{applicationId}` | Anyone who can open the app in the editor |
| `POST /api/v1/approvals/applications/{applicationId}/submit` (optional body `{"note": "..."}`, at most 500 characters, kept as the SUBMITTED event's comment and shown in the history) | Dev, Admin, Approver |
| `POST /api/v1/approvals/applications/{applicationId}/approve` | Approver, Admin; never an MCP token |
| `POST /api/v1/approvals/applications/{applicationId}/reject` (body `{"comment": "..."}`, required) | Approver, Admin; never an MCP token |
| `GET /api/v1/approvals/pending` | Approver, Admin |

`ApproverReadOnlyWebFilter` lets Approvers call submit, approve and reject.

### The frozen version and automatic cancellation

Submitting stores a SHA-256 fingerprint (`ApprovalFingerprint`) of everything a publish copies from edit mode to view
mode: the page list, every page with its layouts and DSL, every query and JS object, the app layout and settings, the
custom JS libraries and the theme's content. Timestamps, permissions and "saved by" fields are left out and entries
are sorted, so the same content always gives the same value. The first 12 characters are shown as the "version".

- **Cancellation:** every read of the state (the editor chip, which refreshes every 15 s while a request is open, the
  approvers' list, approve and reject) compares the stored fingerprint with the app as it is now. On a mismatch a
  submitted request is cancelled (history: "The app changed after it was submitted") and the app is a draft again; an
  approved app becomes a draft. Because it compares stored content rather than hooking write paths, it catches every
  kind of change: the editor, import, git pull, AI and MCP.
- So `GET` status and `GET /pending` may write: they cancel a stale request. Those writes are guarded by the same
  `revision` check as every other state change, so a read that loses the race leaves the winner's state in place.
- **Approve publishes exactly the submitted version:** it refuses when the edit-mode fingerprint no longer matches,
  then claims the request (Submitted to Approving, bumping the revision), then publishes through the normal publish
  path (`ApplicationPageService.publish(id, true)` inside `PublishApproval.asApprovedRelease`, so `lastDeployedAt` is
  set and Users see the app), then checks that the view-mode copy has the submitted fingerprint. If a change landed
  while the publish ran, the app is taken offline (`PublishApproval.markNotLive`), the request is cancelled and
  approve answers an error, so Users never see an unapproved version. If the publish fails, the request goes back to
  Submitted and can be approved again.
- While the claim is held, a reject, a resubmit or a second approve fails cleanly ("not waiting for approval" or the
  revision check), so the stored state always matches what is live. A claim older than 10 minutes (a server that
  stopped mid-publish) goes back to Submitted on the next read.
- State changes are compare-and-set on a `revision` field, so two reviewers (or a review and a resubmit) cannot both
  win.
- A DSL migration on page load after a server upgrade changes the stored DSL, so it also cancels an open request (the
  Dev submits again).

### MCP

`submit_for_approval` (with an optional `note`) and `get_approval_status` are MCP tools
(`app/client/packages/mcp/src/ee/extensions/`), so an AI can submit once the Dev has finished UAT. Approve and reject
are not MCP tools, the MCP allowlist (`filters/McpAllowlistExtensions.java`) only lets an MCP token reach the status
and submit routes, and the service refuses approve and reject for MCP tokens as well.

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
import, and the projects repo's `/api/v1/projects-repo/...`), export, the release approval endpoints (`/api/v1/approvals/...`: submit, approve, reject), git, datasources,
app create, clone, fork, delete and snapshots, and user and admin settings. The Approver filter still applies on top.
This filter does not refuse `POST /api/v1/applications/publish/{id}`, but the publish service does, for every role:
an app is published only by approving a submitted release (Submit -> Approve, see "Publishing").

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

## Projects repo (cab-sync)

Apps live in a separate git repository (the projects repo; a local folder for now, CodeCommit later), one folder per
app, split into diff-friendly files. `tools/cab-sync` (TypeScript on Node 24, self-contained, see its README) moves
them in and out: `init`, `pull`, `push`, `diff`, `submit`. The flow: pull -> edit the files -> push -> test in the
editor's preview -> submit -> a human approves in the app.

**Design: the CLI is an MCP client.** It calls the instance's MCP server (`/mcp`) with the user's own `mcp_` key
(`CAB_TOKEN`; `CAB_URL` for the instance), and uses the MCP tools `list_workspaces`, `list_applications`,
`export_application`, `import_application` and `submit_for_approval`. The Appsmith server only treats `Bearer mcp_...`
as MCP together with `X-Appsmith-Mcp-Internal` (`McpTokenAuthentication.isMcpPrincipal`), which only the MCP server
knows, so the CLI never holds that secret, the rule is unchanged, and an MCP key stays confined to the MCP allowlist.
No token is written to disk or to the repo.

Server (`projectsrepo/`):

| Endpoint | What | Who |
| --- | --- | --- |
| `GET /api/v1/projects-repo/applications/{applicationId}` | The edit copy as app JSON (`ResponseDTO`), from the version-control export, so it needs the edit permission, as Appsmith's git export does. (The file export `/api/v1/applications/export/{id}` needs the export permission, which only ADMIN has, so a Dev could not pull with it.) | Dev, Admin, Approver |
| `POST /api/v1/projects-repo/applications/import/{workspaceId}[?applicationId=]` | App JSON (body `application/json`) as a new app, or over the edit copy of `applicationId` (see "Publishing"). Answers `applicationId`, `workspaceId`, `name`, `unconfiguredDatasources`. | Dev, Admin (the normal import permissions; the Approver filter refuses it) |

`EditCopyJson` keeps every secret on the instance: the export never carries the published copy, the stored datasource
structure, decrypted fields or modified-resources bookkeeping; each datasource is reduced to `name` and `pluginId`; a
query that uses a saved datasource refers to it without its configuration; an API query with its own URL keeps the
URL, headers and parameters (they are the query) but never its authentication. The MCP tool strips the same again,
and cab-sync writes datasources as name and plugin only. `McpAllowlistExtensions` allows exactly these two routes
(prefix `/api/v1/projects-repo`, `import` reserved as a segment).

The MCP server of a fork-uat instance runs from `scripts/fork-uat` (`up.ps1`, `127.0.0.1:<Port+6>`, proxied at
`/mcp`), once MCP is enabled on the instance.

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
- New server code uses only the database-neutral query API (the repositories' `queryBuilder()` / `BridgeQuery` /
  `BridgeUpdate` and the `Custom*RepositoryImpl` helpers), never raw Mongo types (`org.bson.*`,
  `org.springframework.data.mongodb.*`, `ReactiveMongoTemplate`, `Criteria`, `Query`, `Update`), because the fork is
  moving to Postgres. Existing upstream code that uses them stays as it is.
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
| `APPSMITH_AI_ENABLED` | `false` | Switch for the built-in AI (see "Built-in AI switch" below). MCP is not affected. Read at start-up: restart the server after changing it. |
| `APPSMITH_GIT_ENABLED` | `false` | Switch for Appsmith's built-in Git (see "Hidden features" below). Read at start-up: restart the server after changing it. |

## Built-in AI switch

The built-in AI features are **off by default**. `APPSMITH_AI_ENABLED=true` (Spring property `appsmith.ai.enabled`,
`BuiltInAiConfig`) turns them on; they then behave as upstream, with the role gates (Dev and Admin only) still applying.

While it is off:

- The server refuses the built-in AI endpoints with `FEATURE_DISABLED` (HTTP 403, `AE-APP-4030`, "The built-in AI
  is disabled on this instance."): `POST /api/v1/users/ai-assistant/request` (`AIAssistantServiceImpl`, checked before
  the role gate) and every `/api/v1/tenants/ai-config` endpoint: get, update, test-connection, fetch-models and
  test-api-key (`AIConfigServiceImpl`).
- The server sends the switch to the client as the feature flag `fork_builtin_ai_enabled`
  (`FeatureFlagServiceImpl.getAllFeatureFlagsForUser`). It is added after the cached flags, so a cached value never
  wins.
- The client (`ee/selectors/builtInAiSelectors.ts`, `getIsBuiltInAiEnabled`) hides:
  - the Ask AI button and the "Ask AI" slash command in the query, JS and property code editors, and the AI side
    panel (`ee/selectors/aiAssistantSelectors.ts` reports no API key, AI disabled and the panel closed);
  - the "AI Assistant" admin settings page (`ce/pages/AdminSettings/config/index.ts`);
  - the "AI Integrations" section of the create-datasource list, including OpenAI, Anthropic and Google AI
    (`AIPlugins.tsx`; Appsmith AI was already hidden there upstream);
  - the custom widget builder's AI tab (also behind the upstream flag `release_custom_widget_ai_builder`, false).
  The client does not request the AI settings, and a prompt that still reaches the saga fails at once.
- Not covered: datasources of the AI plugins that already exist stay usable in queries (a fresh install has none).
- MCP (the `/mcp` endpoint, per-user `mcp_` tokens for Dev and Admin, and the "MCP keys" profile page) does not read
  the switch and works either way.

The upstream integration tests run with `appsmith.ai.enabled=true` (`application-test.properties`); the disabled
default is covered by `BuiltInAiConfigTest`, `AIAssistantServiceImplTest`, `AIConfigServiceImplTest` and
`FeatureFlagServiceImplTest`, and on the client by `ee/selectors/aiAssistantSelectors.test.ts`,
`ce/sagas/AIAssistantSagas.test.ts` and `IntegrationEditor/__tests__/aiPlugins.test.tsx`.

The upstream integration tests run with `appsmith.cloud_services.enabled=true`
(`app/server/appsmith-server/src/test/resources/application-test.properties`), because they expect cloud services.
The fork's disabled default is covered by unit tests of the `*Impl` classes. Test runs never reach Appsmith's cloud:
the same file sets `appsmith.cloud_services.base_url` and `signature_base_url` to `http://127.0.0.1:1`, a closed
loopback port. Unmocked calls, such as instance registration at start-up, fail locally (the log shows
"Instance registration failed ... Connection refused: /127.0.0.1:1", never "Registration successful"). Suites that need
cloud responses set their own MockWebServer URL (`ApplicationTemplateServiceTest`,
`ApplicationTemplateServicePublishTemplateTest`).

## Hidden features

The fork hides what it does not use: the code stays, but no screen, menu item or route leads to it, and the server
refuses its endpoints. Where to look:

- **Server:** `hidden/HiddenEndpointsWebFilter` refuses the endpoints with `FEATURE_DISABLED` (HTTP 403,
  `AE-APP-4030`, "{feature} is disabled on this instance."). It runs after Spring Security, so an anonymous caller still
  gets 401 first.
- **Client:** `ce/utils/forkHiddenFeatures.ts` (`isWorkspaceUiHidden`, `areTemplatesHidden`, `isOnboardingHidden`,
  always true) and, for Git, `ce/selectors/builtInGitSelectors.ts` (`getIsBuiltInGitEnabled`). Every hidden entry point
  checks one of them, with a `Fork:` comment.

### Workspaces: one hidden workspace

There is one workspace, "Apps" (see "Roles"), and users never see it as a workspace.

- Hidden: the workspace list and "create workspace" on the home page (also on mobile), the workspace menu (settings,
  rename, delete, leave, members, invite), the member avatars and the invite button, the `/workspace/...` settings and
  members pages, the "Workspaces" section of the home search, "Fork" on the app card (it copies an app into a workspace
  picked from a list; "Duplicate" stays), and the invite form in the editor's and the viewer's Share dialog (the app URL
  and the public toggle stay; the dialog is titled "Share application").
- The home page shows the apps of the default workspace directly, picked by `defaultWorkspaceId` from `/users/me`; the
  "Create new" menu (new app, import) stays. Favourites are not listed separately.
- Kept for the Admin: users and roles live in **Admin settings > User roles** (`GET /api/v1/admin/users`,
  `PUT /api/v1/admin/users/{userId}/role`). New users join by signing up (they become User); nobody is invited into a
  workspace any more.
- Server: `POST /api/v1/workspaces` and `DELETE /api/v1/workspaces/{id}` are refused for everyone. The fork's startup
  code (`DefaultWorkspaceService`) creates "Apps" straight through the repository, so it is not affected, and neither
  are server-internal calls of `WorkspaceService`. Other workspace endpoints (read, members, rename, logo,
  `/users/invite`) are not refused; nothing in the UI reaches them.

### Templates

- Hidden: the template gallery (`/templates`), "Start from template" on the home page, "Add page from template" in the
  editor's add-page menu, and the templates modal the editor mounted on every load (it fetched the template list and
  filters each time). "Fork template" lived only inside those screens. Building blocks stay off behind the upstream
  flag `release_drag_drop_building_blocks_enabled` (false).
- No request to `/api/v1/app-templates` is made: the template sagas are not registered, and the request interceptor
  `api/interceptors/request/blockHiddenRoutes.ts` answers any such request locally (as for an airgapped instance).
- Server: every `/api/v1/app-templates` endpoint is refused. With cloud services off they already returned nothing.

### Onboarding

- Hidden: the profiling form after signup ("What will you build?"), the first-app screen a new user got instead of the
  app list (`CreateNewAppsOption`, "connect your data"), the onboarding checklist page (`/edit/checklist`) and the
  signposting overlay. Feature walkthroughs were already off (`rollout_remove_feature_walkthrough_enabled`), and the
  checklist was never started in CE.
- Kept: the first admin signup (`/setup/welcome`, `SetupForm`), the login and signup pages.

### Built-in Git (`APPSMITH_GIT_ENABLED`, off by default)

The switch is `BuiltInGitConfig` (Spring property `appsmith.git.enabled`). The server sends it to the client as the
feature flag `fork_builtin_git_enabled` (`FeatureFlagServiceImpl`, after the cached flags, like the AI switch).

While it is off:

- The server refuses every `/api/v1/git/**` endpoint (connect, commit, branches, pull, merge, discard, protected
  branches, auto-commit, deploy keys, import from Git, Git profiles) and `/api/v1/applications/ssh-keypair/{id}`.
  The MCP server's Git tools get the same error.
- The client hides: the Git modals in the editor and on the home page (connect, ops, settings, disconnect, import, repo
  limit), the bottom-bar Git actions ("Connect Git", branch, commit, pull), "Connect to Git repository" in the deploy
  menu, the "Import from Git" card, the Git keyboard shortcut, the Git author fields in Admin settings > Profile and
  the "Git user config" profile tab. The editor makes no Git request when it loads (`AppEditorEngine.loadGit`).
- Left as is: UI that only shows for an app that is already connected (Git badge on the app card, protected-branch
  callout, Deploy opening the Git modal). No app can be connected while the switch is off, and app exports carry no
  Git metadata.

`APPSMITH_GIT_ENABLED=true` restores upstream behaviour; the section "Git without the cloud" below then applies. The
upstream integration tests run with it on (`application-test.properties`); the default is covered by
`HiddenEndpointsWebFilterTest`, `HiddenEndpointsHttpTest` and `FeatureFlagServiceImplTest`, and on the client by
`ce/pages/Applications/tests/hiddenFeatures.test.tsx`.

Two upstream HTTP tests changed with the refused workspace creation: `WorkspaceControllerTest` now expects the 403, and
`WorkspaceIdentityAuthGuardTest` (GHSA checks of the create request body) is disabled, since the request never reaches
the controller.

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

Dev instances from source (`scripts/fork-uat/`, see its README): a worktree whose `app/client/package.json` and
`yarn.lock` match the main folder's links the main folder's client `node_modules` (one junction per entry, workspace
packages and `.cache` kept its own) instead of a 30-minute `yarn install`; `up.ps1 -NoLink` installs instead. Once
MCP is enabled on the instance, `up.ps1` also runs the MCP server (`127.0.0.1:<Port+6>`, proxied at `/mcp`).

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

`fork_builtin_ai_enabled` is not in `ForkFeatureFlagDefaults`: its value is `APPSMITH_AI_ENABLED`, added by
`FeatureFlagServiceImpl` (see "Built-in AI switch"). The same goes for `fork_builtin_git_enabled`
(`APPSMITH_GIT_ENABLED`, see "Hidden features"); the Git flags above only matter when it is on. And for
`fork_editor_read_only`: `FeatureFlagServiceImpl` sets it from `APPSMITH_EDITOR_READ_ONLY` on every request, whatever
the cache or the cloud says (see "Read-only editor").

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

Applies when built-in Git is switched on (`APPSMITH_GIT_ENABLED=true`; off by default, see "Hidden features").

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
  `CRUD-DB-Table-Template-Application.json`, `DEFAULT_ORIGIN_HEADER` (`app.appsmith.com`, a redirect-domain fallback
  when a request has no Origin, Referer or Host), `ai.appsmith.com` as the old Appsmith AI datasource URL (migration 047 and the
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
  "request a template" link in `pages/Templates/Template/RequestTemplate.tsx` (an Appsmith-hosted form inside the
  template gallery, which is hidden, see "Hidden features").
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
- `CRUD-DB-Table-Template-Application.json` in the server jar: the `release.app.appsmith.com` strings only. Fixed in
  the hidden-features task: its two `defaultImage` URLs and the "Generate page from data" success images
  (`constants/Assets.java`, `crud/workflow_sql.svg` and `workflow_s3.svg`) point at the vendored `/assets-cdn` copies.
- `DEFAULT_ORIGIN_HEADER` (`https://app.appsmith.com`): only `RedirectHelper.getRedirectDomain`'s fallback for a
  request with no Origin, Referer and Host header, which HTTP/1.1 clients always send. Fixed in the hidden-features
  task: a failed first-admin signup without a `Referer` now redirects to this instance's `/user/login`
  (`UserSignupCEImpl`, covered by `UserSignupFailureRedirectTest`); the user signup already used a relative path.

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
- Edited, server: `services/FeatureFlagServiceImpl.java` (and `FeatureFlagServiceImplTest`),
  `src/main/resources/application-ce.properties`, `src/test/resources/application-test.properties`.
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
  client sends on load stay in the client instead of hitting the server guard; until the feature flags have
  loaded they wait for them, so an early sync is never sent),
  `CommonEditorForm/InfoFields.tsx` and `components/EmbeddedDatasourcePathField.tsx` (the API URL field
  follows the action's edit permission and hides "Save URL" when disabled)

### Built-in AI switch

- New: server `configurations/BuiltInAiConfig.java`, tests `configurations/BuiltInAiConfigTest`,
  `services/AIAssistantServiceImplTest`, `services/AIConfigServiceImplTest`, `services/FeatureFlagServiceImplTest`;
  client `src/ce/selectors/builtInAiSelectors.ts`, `src/ee/selectors/builtInAiSelectors.ts`,
  `src/ee/selectors/aiAssistantSelectors.test.ts`.
- Edited upstream files: `deploy/docker/fs/opt/appsmith/templates/docker.env.sh`; server
  `exceptions/AppsmithError.java`, `exceptions/AppsmithErrorCode.java` (`FEATURE_DISABLED`),
  `services/AIAssistantServiceImpl.java`, `services/AIConfigServiceImpl.java`, `services/FeatureFlagServiceImpl.java`,
  `resources/application-ee.properties`, test `resources/application-test.properties`; client
  `src/ce/entities/FeatureFlag.ts`, `src/ee/selectors/aiAssistantSelectors.ts` (was a plain re-export),
  `src/ce/sagas/AIAssistantSagas.ts` (and `.test.ts`), `src/ce/pages/AdminSettings/config/index.ts`,
  `src/pages/Editor/IntegrationEditor/AIPlugins.tsx` (and `__tests__/aiPlugins.test.tsx`),
  `src/pages/Editor/CustomWidgetBuilder/Editor/index.tsx`.

### Release approvals

- New server files: `approvals/` (controller, service, fingerprint, DTO, status, event),
  `domains/ApplicationApproval.java`, `repositories/ApplicationApprovalRepository.java`,
  `CustomApplicationApprovalRepository.java`, `CustomApplicationApprovalRepositoryImpl.java`, and the test
  `approvals/ApplicationApprovalServiceTest`.
- Edited server files: `constants/Url.java` (`APPROVAL_URL`), `filters/McpAllowlistExtensions.java` (the MCP rules;
  an empty subclass upstream), `roles/ApproverReadOnlyWebFilter.java`, `roles/PublishApproval.java`
  (`asApprovedRelease`), `services/ApplicationPageServiceImpl.java` (direct publish refused),
  `imports/internal/ImportServiceImpl.java` (import over an existing app replaces the edit copy only; a snapshot
  restore over a live app takes it offline), and the test `roles/InstanceRolePermissionsTest`.
- New client files: `src/ee/api/ApprovalApi.ts`, `src/ee/utils/approvals.ts` (and `.test.ts`),
  `src/ee/components/approvals/ApprovalStatusChip.tsx`,
  `src/ee/pages/AppIDE/layouts/components/Header/ReleaseControls.tsx` (and `.test.tsx`), `useApplicationApproval.ts`,
  `src/ee/pages/Applications/PendingApprovals.tsx` (and `.test.tsx`).
- Edited upstream client files: `src/pages/AppIDE/layouts/components/Header/index.tsx` (`ReleaseControls` in place of
  `DeployButton`), `src/ce/pages/Applications/index.tsx` (`PendingApprovals` above the app list).
- MCP: `packages/mcp/src/ee/extensions/api.ts`, `catalog.ts`, `tools.ts`, `routeContract.ts` (re-exports of `ce/`
  upstream, now the fork's implementation), the test `approvals.test.ts`, and `packages/mcp/README.md`.

### Projects repo

- New: `tools/cab-sync/` (CLI, tests, README); server `projectsrepo/` (`ProjectsRepoController`,
  `ProjectsRepoService`, `EditCopyJson`) and the tests `projectsrepo/ProjectsRepoServiceTest`,
  `projectsrepo/EditCopyJsonTest`; MCP test `packages/mcp/src/ee/extensions/projectsRepo.test.ts`.
- Edited fork files: `constants/Url.java` (`PROJECTS_REPO_URL`), `filters/McpAllowlistExtensions.java`,
  `imports/internal/ImportServiceImpl.java`, `approvals/ApprovalFingerprint.java` (a side without a copy, such as a
  query an import added to a live app, no longer breaks the fingerprint), `approvals/ApplicationApprovalService.java`
  and `ApplicationApprovalController.java` (submit note), the MCP `ee/extensions/` files, client
  `src/ee/components/approvals/ApprovalStatusChip.tsx` (shows the submit note) and
  `ReleaseControls.test.tsx`, `scripts/fork-uat/` (`up.ps1`, `down.ps1`, `README.md`: the MCP server), and the
  tests `approvals/ApplicationApprovalServiceTest`, `roles/InstanceRolePermissionsTest`,
  `filters/McpAllowlistWebFilterTest`.

### Hidden features (workspace UI, templates, onboarding, built-in Git)

- New: server `configurations/BuiltInGitConfig.java`, `hidden/HiddenEndpointsWebFilter.java`, tests
  `hidden/HiddenEndpointsWebFilterTest`, `hidden/HiddenEndpointsHttpTest`, `solutions/UserSignupFailureRedirectTest`;
  client `src/ce/utils/forkHiddenFeatures.ts`, `src/ee/utils/forkHiddenFeatures.ts`,
  `src/ce/selectors/builtInGitSelectors.ts`, `src/ee/selectors/builtInGitSelectors.ts`,
  `src/api/interceptors/request/blockHiddenRoutes.ts` (and `.test.ts`),
  `src/ce/pages/Applications/tests/hiddenFeatures.test.tsx`, `src/ce/pages/AppIDE/hooks/useGenPageItems.test.tsx`,
  `src/pages/AppIDE/components/GitModals.test.tsx`.
- Edited upstream files: `deploy/docker/fs/opt/appsmith/templates/docker.env.sh`; server `constants/Assets.java`,
  `services/FeatureFlagServiceImpl.java`, `solutions/ce/UserSignupCEImpl.java`,
  `resources/CRUD-DB-Table-Template-Application.json`, `resources/application-ee.properties`, tests
  `configurations/WorkspaceIdentityAuthGuardTest.java` (disabled), `controllers/WorkspaceControllerTest.java`,
  `services/FeatureFlagServiceImplTest.java`, `resources/application-test.properties`; client
  `src/api/interceptors/request/apiRequestInterceptor.ts`, `index.ts`, `src/ce/AppRouter.tsx`,
  `src/ce/entities/FeatureFlag.ts`, `src/ce/pages/AppIDE/components/AppIDEModals.tsx`,
  `src/ce/pages/AppIDE/hooks/useGenPageItems.tsx`, `src/ce/pages/AppIDE/layout/routers/MainPane/constants.ts`,
  `src/ce/pages/Applications/index.tsx`, `WorkspaceAction.tsx` (and `tests/WorkspaceAction.test.tsx`),
  `src/ce/utils/signupHelpers.ts`, `src/components/BottomBar/index.tsx`, `src/constants/userConstants.ts`,
  `src/entities/Engine/AppEditorEngine.ts`, `src/pages/AdminSettings/Profile/index.tsx`,
  `src/pages/AppIDE/components/GitModals.tsx`, `src/pages/AppIDE/layouts/components/Header/ChevronMenu.tsx`,
  `Header/index.tsx`, `src/pages/AppViewer/Navigation/components/ShareButton.tsx`,
  `src/pages/Applications/ApplicationCard.tsx`, `src/pages/Editor/GlobalHotKeys/GlobalHotKeys.tsx`,
  `src/pages/UserProfile/index.tsx`, `src/pages/common/ImportModal.tsx`,
  `src/pages/common/SearchBar/WorkspaceSearchItems.tsx`, `src/pages/setup/SignupSuccess.tsx` (and `.test.tsx`),
  `src/pages/workspace/AppInviteUsersForm.tsx`, `src/sagas/TemplatesSagas.ts`.

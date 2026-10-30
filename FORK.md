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

## PostgreSQL (in progress, branch `fork/postgres`)

The internal database moves from MongoDB to PostgreSQL. Fresh installs only; there is no Mongo data import, and the
Mongo path is dropped (not kept compiling): `APPSMITH_DB_URL` must be `postgresql://user:password@host:port/db`.

**Rule: new code uses only the database-neutral query API** (`Bridge`, `BridgeQuery`, `BridgeUpdate`,
`queryBuilder()`, the repository interfaces). Never import `org.springframework.data.mongodb`, `com.mongodb` or
`org.bson`, and never write SQL outside `repositories/postgres`.

Design (blueprint: upstream's abandoned `pg` branch, Apache 2.0; its entity annotations and jsonb types are reused):

- Entities are JPA/Hibernate classes; nested objects are `jsonb` columns (hypersistence-utils, `CustomJsonType`).
  Table and column names are the quoted snake_case of class and field names (`SnakeCaseNamingStrategy`).
- The repositories keep their **reactive API** (Mono/Flux), unlike `pg`, which rewrote ~300 service files to
  generated "cake" wrappers. `repositories/postgres` implements the repository interfaces itself
  (`@EnableAppsmithRepositories`): `BaseRepositoryImpl` for CRUD, the `Custom*Impl` fragments as before, and
  `PartTreeAppsmithQuery` for derived methods (`findByEmail`, ...). Blocking JDBC runs on `boundedElastic`, one
  transaction per call (`PostgresOperations`). So services stay as upstream wrote them and cherry-picks keep applying.
- `BridgeQuery`/`BridgeUpdate` are plain data now; `SqlRenderer` turns them into SQL with MongoDB's matching rules
  (array fields match on any element, negated checks match missing fields, nested keys address jsonb).
- The ACL check is `jsonb_exists_any` on `policy_map`, built by the `permissionCriteria` hook of
  `BaseAppsmithRepositoryCEImpl`; `CustomApplicationRepositoryImpl` overrides it for "Users see only live apps".
- Ids keep MongoDB's 24-hex format (`ObjectIds`), so ids embedded elsewhere (MCP user keys) are unchanged.
- Flyway owns the schema (schema `appsmith`): `db/migration/postgres/V1__baseline.sql` (tables generated from the
  entities, then edited: `text` columns, no enum CHECKs, NOT NULL only on keys, the indexes the code relies on) and
  `migrations/postgres/V2__seed_fresh_install.java`, which loads `db/seed/fresh-install.json` (plugins, themes, the
  organization with its policies, the instance roles, the anonymous user, instance config; a snapshot of a real fresh
  Mongo install of this fork after all Mongock migrations) and generates the `instance-id`. Mongock and its
  changelogs are gone; later schema or data changes are new Flyway versions.
- Mongo-only operations became SQL in `Custom*RepositoryCEImpl`: publishing pages and actions is one UPDATE that copies
  the unpublished column, the first-admin setup lock is `INSERT ... ON CONFLICT DO NOTHING` on
  `super_user_setup_lock`, the sequence is an upsert, `setDefaultPage` rewrites the `pages` jsonb array.
- Transactions: the `TransactionalOperator` bean is `PostgresTransactionalOperator`. `transactional(...)` opens one JPA
  transaction on a thread of its own and puts it in the Reactor context; every `PostgresOperations` call inside runs on
  that thread and joins it, so an import (or any other upstream `transactional` block) commits or rolls back as a
  whole. It commits when the publisher completes, rolls back on error or cancel; a nested `transactional` joins the
  outer one.
- Unique violations (SQLSTATE 23505) become Spring's `DuplicateKeyException` with the constraint name in the message,
  and a failed insert resets the id `save` generated to null, as on MongoDB, so a retry (with a suffixed name, say)
  inserts again instead of looking the object up. Services find name clashes by Mongo index
  name, so `V3__unique_name_indexes.sql` creates `workspace_app_deleted_gitApplicationMetadata` and
  `workspace_datasource_deleted_compound_index` under those names (`NULLS NOT DISTINCT`, PostgreSQL 15+). This keeps
  "App (1)" naming on fork and import. The "An object with the name … already exists" message takes the name from
  PostgreSQL's `Key (…)=(…)` detail (`DuplicateKeyExceptionUtils`), as it did from Mongo's `dup key`.
- Soft delete: entities have no Hibernate `@Where(deleted_at IS NULL)`; every repository query filters `deleted_at`
  itself. With the filter, saving an archived row could not see it and inserted it again (duplicate primary key).
- `APPSMITH_ADMIN_EMAILS` is applied at every start by `migrations/SuperUserSync` (an `ApplicationRunner`, replacing
  Mongock's `Migration10000`): the listed users become exactly the instance and organization admins, missing users are
  created disabled, and an empty value removes every admin, as upstream did.
- Release approvals are the `application_approval` table (`V4__application_approval.sql`).
- App tags are a `jsonb` array column with a GIN index, and one live team per name (ignoring case) is a partial unique
  index (`V7__app_tags_and_team_names.sql`; Mongo had Mongock `Migration077`'s tags index and only `TeamService`'s
  check-write-recheck). A whole JSON column checked against a scalar (`equal`/`in`, as the catalog's tag filters and
  `assignedToUserIds` do) renders as `@>`, which the GIN indexes serve; `searchIgnoreCase` on JSON matches any string
  element. `TeamService` turns the index's refusal into its duplicate-name error. The grant code writes `policyMap`
  only (`policies` is not a column).
- Not ported: the EE-downgrade guard (it read Mongock's audit log). The plugins' own Mongo use is untouched (they talk
  to user databases, not the internal one).
- `scripts/fork-uat/up.ps1 -DbType postgres` (the default in a worktree that has `db/migration/postgres`) runs the instance
  on its own PostgreSQL 16 container, `appsmith-dev-<Name>-postgres` on `127.0.0.1:<Port+5>`. In such a worktree
  `-DbType mongo` is refused (the server would stop on the URL), and an instance that last ran on MongoDB (its
  `instance.json` says `mongo`, or has no `dbKind`) moves to a new, empty PostgreSQL database, with a warning, on its
  next `up.ps1` or `up.ps1 -Restart server`: the database step runs, the instance's Redis is emptied once (sessions
  and the cached organization belonged to the MongoDB data), the dev admin is created again, and the MongoDB
  database `appsmith_<Name>` in the shared `appsmith-dev-mongo` is left untouched. No data is copied.
- **The main folder's instance on 8780** runs on MongoDB today. Once this branch is merged into `release` and the
  main folder is updated, nothing needs to be done by hand: stop it as usual (`.\scripts\fork-uat\down.ps1`), then
  start it with a plain `.\scripts\fork-uat\up.ps1 -Port 8780` (or `-Restart server`). It comes up on PostgreSQL
  `appsmith-dev-appsmith-postgres` on `127.0.0.1:8785`, empty but for the dev admin; its test apps stay in MongoDB
  (`appsmith_appsmith`) and are not moved. To bring a test app along, export it before the switch and import it
  after. Once it is not needed, the old database can be dropped with
  `docker exec appsmith-dev-mongo mongosh --quiet appsmith_appsmith --eval "db.dropDatabase()"` (the container is
  shared with other worktrees; drop only that database).

Status (milestone 1): on a `-DbType postgres` fork-uat database the server applies V1–V7 and passes an end-to-end check
over HTTP: first-admin signup, app creation, export, import as a new app, direct publish refused, submit and approve
publishes, an admin import onto the live app turns the approval back to draft, a Dev cannot import over the live app,
publish or approve but can submit, the admin rejects, and the published version stays as approved. The full
`up.ps1 -DbType postgres` instance (dependencies linked from the main folder, webpack client) also passes in a headless
browser: the login page, a Dev's "Submit for approval" in the editor, the admin's Approve in the home page's "Waiting for
approval" list, and the published page then carrying the Dev's change. Tested on Testcontainers PostgreSQL: the repository tests,
imports and exports (`ImportServiceTests` 64, `ExportServiceTests` 23, partial import/export 4 + 4,
`ImportApplicationTransactionServiceTest` 2), forking (`ApplicationForkingServiceTests` 19), release approvals
(`ApplicationApprovalServiceTest` 49, `InstanceRolePermissionsTest` 83, `EditorReadOnlyHttpTest` 5), the projects repo
(`ProjectsRepoServiceTest` 19, `EditCopyJsonTest` 39), teams and per-app grants (`AppGrantServiceTest` 22,
`TeamServiceTest` 13, `DefaultWorkspaceServiceTest` 8, `PolicyGeneratorTest` 9), app tags and the catalog
(`ApplicationCatalogTest` 26, `ApplicationTagsTest` 8, `McpAllowlistWebFilterTest` 36) and the Postgres
tests (transactions, unique names including team names, soft delete, admin sync, JSON array queries and the tags index). The datasource plugins postgres, mysql, mssql and REST API
and JS objects run on a live server in edit and view mode.
The REST API plugin refuses loopback hosts (SSRF filter); for local tests only, `APPSMITH_DISABLE_SSRF_FILTER=true`.

Server test suite on PostgreSQL (`appsmith-server`, Testcontainers, one JVM per batch as in CI), in batches by
package, after merging `release` (#289, #290, #294, then #291, #292, #295). Classes, not test methods:

| Batch | Packages | Passed | Failed | Skipped |
|---|---|---|---|---|
| 1 | every package but those below (acl … widgets, approvals, catalog, controllers, grants, imports, exports, projectsrepo, roles), `ServerApplicationTests` | 72 | 0 | 0 |
| 2 | `repositories`, `configurations` | 33 | 0 | 1 |
| 3 | `helpers` | 32 | 0 | 0 |
| 4 | `solutions` | 19 | 0 | 0 |
| 5 | `git` | 13 | 2 | 1 |
| 6a | `services.ce` | 26 | 0 | 0 |
| 6b | `services` (the rest) | 34 | 0 | 1 |
| | **Total** | **229** | **2** | **3** |

Each batch is from one run after the merge of #291, #292 and #295. The MCP package (`packages/mcp`, jest 1597 passed,
5 skipped, `tsc` clean) and `tools/cab-sync` (33 passed) don't touch the database and pass unchanged.
History note: the WIP commit `ee105d20d3` ("V5 adds Mongo's remaining unique indexes") says batch 4 still fails
(`CreateDBTablePageSolutionTests`, `ActionExecutionSolutionCETest`) and batches 5 and 6 were not run. That was the
state at that commit only; later commits fixed those failures and ran every batch, as the table shows.

Fixed in the tests after the first run (none came from the port):
- `UserWorkspaceServiceUnitTest` leaves out the fork's default "Apps" workspace, which `api_user` also sees.
- `UserServiceWithDisabledSignupTest` checks that a new admin joins the default workspace's administrator group (the
  fork creates no "'s apps" workspace). Its `@AfterAll` now really turns signup back on; upstream's never did, so
  `WorkspaceServiceTest` later in a batch got "Signup is restricted".
- `DefaultWorkspaceServiceTest` gets a context of its own: a cached context keeps the ids it bootstrapped with, while
  another context may have replaced the stored default workspace since.
- `SearchEntitySolutionCETest` (no search string) expects its app first, not alone: apps other classes left in the
  shared default workspace are visible too.
- `MockDataServiceTest` and `ActionExecutionSolutionCETest.executeAction_actionOnMockDatasource_success` are
  `@Disabled`: they need the cloud's mock datasources, which are off by design ("Sample databases" below).
- The test seed (`SeedMongoData`) keeps the default workspace. It deleted every workspace at each new context's start,
  so each context bootstrapped its own "Apps" and the contexts cached earlier kept stale ids.
- `UserServiceWithDisabledSignupTest.createNewUserValidWhenDisabled` uses a unique email: `UserServiceTest` creates the
  same one earlier in the batch, and an existing user is refused before the signup check.
- `ActionExecutionSolutionCETest` accepts a `requestedAt` equal to `Instant.now()`: on Windows the two can be the same.

Failed, not from the port: `AutoCommitServiceTest` and `AutoCommitSolutionImplTest` (1 test each, the client DSL
migration) fail on Windows only. Upstream's
`FileUtilsCEImpl.getExistingFilesInRepo` / `isWhiteListedPath` and `getGitResourceIdentity` split the repository's
relative paths on `/`, but on Windows they come as `pages\Page1\...`, so no page file is read back and nothing is
committed. On Linux (CI, the Docker image) the paths use `/`: run on Linux in `maven:3.9-eclipse-temurin-25` (the
worktree copied into the container, a throwaway PostgreSQL 16 through `APPSMITH_TEST_DB_URL` instead of
Testcontainers, a throwaway Redis), both pass (`AutoCommitServiceTest` 7, `AutoCommitSolutionImplTest` 10 with its 2
`@Disabled` skipped).
The organization is cached in Redis, so flush the test Redis between runs.

Adapted from the suite: tests that read the encrypted password from the saved object
(`DatasourceServiceTest`, `DatasourceContextServiceTest`) now read the stored one (`StoredValues`, as upstream's `pg`
branch did): Mongo's save encrypted the entity in place, the port encrypts only the stored JSON.
`LayoutActionServiceTest` reads nested DSL objects as `Map` (they come back as `JSONObject`, not `LinkedHashMap`).

Fixed from the suite: `V5__remaining_unique_indexes.sql` adds the unique indexes Mongo had beyond names (one cached
structure per datasource and environment, storages, snapshot chunks, custom JS libraries, reset tokens, URL slugs).
V2 now names the seed's columns with `SnakeCaseNamingStrategy`; its own function had turned `generateCRUDPageComponent`
and `isDependentOnCS` into columns that do not exist, so the plugins lost them and "generate CRUD page" was refused.
`V6__plugin_seed_acronym_columns.sql` fills them in on databases seeded before the fix.

Skipped: `MockDataServiceTest` (above). `WorkspaceIdentityAuthGuardTest` is `@Disabled` by the fork (creating a
workspace over HTTP is a hidden endpoint). `GitBranchesWithCentralServiceIT` was left out: it starts the `appsmith/test-event-driver` git server image,
which is not on this PC, and the Testcontainers pull hung for 25 minutes.
Classes whose tests are all `@Nested` (`FSGitHandlerTest`, 31 tests) show 0 tests in surefire's text summary; they are
counted from the XML report.

Test setup: every cached test context has its own connection pool, so the test container allows 400 connections and
each pool holds at most 5 (`PostgresTestContainerInitializer`); with the defaults (100 and 10) the later contexts in a
batch failed with "too many clients".

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
- When the role definitions change in a new server version (`InstanceRole`), the next start brings an instance that
  already runs this fork in line (`RoleGroupReconciler`, idempotent): if a role group stores other permissions than
  its role defines, each role group first loses, on every app of "Apps" and its pages, queries, JS objects and custom
  themes, the permissions its role no longer gives; then the workspace's policies are rebuilt and last the groups store
  their new permissions (so a start that stops halfway does it all again). App grants and other groups are left alone,
  nothing is added, datasources are not touched.

### The four roles

| Role | Can |
| --- | --- |
| Admin | Everything: apps, datasources, users, roles, teams and app grants. Instance administrator. |
| Dev | Create apps; see and edit only the apps they hold a grant on (Edit to edit, View to see and run; every app they create, duplicate or import as a new app gives them Edit). Edit mode, AI and MCP, and use existing datasources in queries. Cannot create, edit, test or delete datasources (sees only name and plugin), publish, or delete apps. |
| Approver | Every app: open the editor or preview read-only, run queries, and publish. Nothing else. |
| User | Only the published apps they hold a grant on. Never edit mode or preview (an Edit grant counts as View), and never an app that was never published. |

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
  what users see anyway, the app is taken offline (`PublishApproval.markNotLive`), as approve does. A file over an
  existing app that is not an app file (`ApplicationJson`) is refused (`UNSUPPORTED_OPERATION`), since there would be
  no published side to drop. `ApplicationApprovalServiceTest` covers these cases.
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

### Teams and app grants

Server code: `app/server/appsmith-server/src/main/java/com/appsmith/server/grants/`.

Dev and User see no app through their role. An Admin gives access per app: a **grant** goes to a **team** or a
**single user**, at level **View** (see the app, run its queries and JS objects) or **Edit** (View plus change the
app, its pages, queries and JS objects). Edit never includes publishing: an app goes live only through Submit ->
Approve. Approver and Admin keep every app through their role.

**After upgrading to this version, Devs and Users see none of the existing apps until an Admin grants them.** No
creator is recorded for apps made before it, so nobody gets an automatic Edit grant on them; give each team or user
their apps (`PUT /api/v1/applications/{applicationId}/grants`, below).

- **Teams** are permission groups with `defaultDomainType` `Team`; their members are the group's `assignedToUserIds`.
  A team holds no permission of its own.
- **A grant to a single user** goes to the user's hidden personal group (`defaultDomainType` `UserGrant`,
  `defaultDomainId` = the user id, the user its only member), created on their first grant, because document
  policies only name permission groups.
- Both kinds count among a user's permission groups (`CacheableRepositoryHelperCEImpl.getPermissionGroupsOfUser`),
  and are also cached apart for `RoleGate` (`getGrantGroupsOfUser`). Adding or removing a member, and creating a
  personal group, clear both of that user's caches, so access changes on their next request.
- **How a grant is stored** (`AppGrantService`): like CE's "make public", in the policies of the app and of its pages,
  queries, JS objects and custom themes, for the grantee's group. View = `read:applications` (pages: read, queries:
  execute); Edit = `manage:applications` (pages and queries: manage). Pages, queries and JS objects created later
  inherit the app's policies, so they inherit its grants; an import over the app keeps the app's policies and grants.
  Datasources are not part of a grant: Dev and User execute every datasource through their role.
- **Creator gets Edit:** a Dev who creates an app, duplicates one or imports a file as a new app (in the editor or
  through MCP / cab-sync) gets an Edit grant on it before it is saved (`ApplicationPageServiceImpl.setApplicationPolicies`).
- **A User never edits** (`roles/RoleGate`): for a User, team and personal grants count only for reading and executing
  apps, pages, queries, JS objects and themes; every other permission check on them leaves those groups out. A User
  also sees only live apps of "Apps", whatever grant they hold (`CustomApplicationRepositoryImpl`).
- App names stay unique in the workspace even across apps a Dev cannot see, so an import picks a free name suffix
  (`ApplicationServiceImpl.isApplicationNameTaken`).
- Git-connected apps: a grant is written on the branch app it is set on; built-in Git is off by default.

| Endpoint | What |
| --- | --- |
| `GET /api/v1/teams`, `GET /api/v1/teams/{teamId}` | Teams with their members (`id`, `email`, `name`) |
| `POST /api/v1/teams` (body `{"name", "description"}`) | Create; names are unique (ignoring case), at most 100 characters |
| `PUT /api/v1/teams/{teamId}` (same body) | Rename and replace the description |
| `DELETE /api/v1/teams/{teamId}` | Delete the team, then revoke every grant it holds; a retry after a failure partway revokes what is left, and deleting a team that is already gone succeeds |
| `PUT` / `DELETE /api/v1/teams/{teamId}/members/{userId}` | Add / remove a member |
| `GET /api/v1/applications/{applicationId}/grants` | The app's grants: `[{grantee: {type: TEAM\|USER, id}, name, level}]` (`name` is the team name or the user's email) |
| `PUT /api/v1/applications/{applicationId}/grants` (body `{"grantee": {"type": "TEAM"\|"USER", "id"}, "level": "VIEW"\|"EDIT"\|"NONE"}`) | Set one grantee's level (`NONE` revokes); answers the app's grants |

All of them are Admin only (403 otherwise); the Approver filter refuses the non-GET ones for Approvers anyway, and
they are not on the MCP allowlist.

Client: **Admin settings > Teams** (`src/pages/AdminSettings/Teams/index.tsx`, next to "User roles") creates,
renames and deletes teams and adds or removes members; grants are given per app from the **Access** item of the app
card's menu on the home page (see "The home page" under "App tags and catalog").

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
| `POST /api/v1/approvals/applications/{applicationId}/submit` (optional body `{"note": "..."}`, at most 500 characters, kept as the SUBMITTED event's comment and shown in the history) | Dev (with an Edit grant on the app), Admin, Approver |
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
import, and the projects repo's `/api/v1/projects-repo/...`), app tags (`/api/v1/app-tags/...`, metadata, see "App
tags and catalog"), export, the release approval endpoints (`/api/v1/approvals/...`: submit, approve, reject), git, datasources,
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
- a banner says "Read-only: edit this app through the projects repo (MCP)." (`EditorReadOnlyBanner`);
- the main pane runs the canvas, except that **Admins** still get the datasource pages there (new datasource, a
  datasource's page with Edit, the list; `pages/AppIDE/layouts/routers/MainPane/MainPane.tsx`). This is where an
  Admin adds and changes datasources, and completes the password of one MCP created. Devs and Approvers keep the
  canvas, and the Data pane tells them an Admin manages datasources (`ee/components/DatasourceManagedNote.tsx`);
  an Admin with no datasources is told to add one with "Bring your data".

The upstream integration tests run with `appsmith.editor.read-only=false` (`application-test.properties`), because
they change apps over HTTP as session users; `EditorReadOnlyWebFilterTest` and `EditorReadOnlyHttpTest` cover it on.

## Projects repo (cab-sync)

Apps live in a separate git repository (the projects repo; a local folder for now, CodeCommit later), one folder per
app, split into diff-friendly files. `tools/cab-sync` (TypeScript on Node 24, self-contained, see its README) moves
them in and out: `init`, `pull`, `push`, `diff`, `submit`. The flow: pull -> edit the files -> push -> test in the
editor's preview -> submit -> a human approves in the app. `push` first compares the folder with the app's edit copy
(as `diff` does) and imports nothing when they match, so an unchanged push leaves an approved or submitted app as it is.

**Design: the CLI is an MCP client.** It calls the instance's MCP server (`/mcp`) with the user's own `mcp_` key
(`CAB_TOKEN`; `CAB_URL` for the instance), and uses the MCP tools `list_workspaces`, `list_applications`,
`export_application`, `import_application` and `submit_for_approval`. The Appsmith server only treats `Bearer mcp_...`
as MCP together with `X-Appsmith-Mcp-Internal` (`McpTokenAuthentication.isMcpPrincipal`), which only the MCP server
knows, so the CLI never holds that secret, the rule is unchanged, and an MCP key stays confined to the MCP allowlist.
No token is written to disk or to the repo.

Server (`projectsrepo/`):

| Endpoint | What | Who |
| --- | --- | --- |
| `GET /api/v1/projects-repo/applications/{applicationId}` | The edit copy as app JSON (`ResponseDTO`), from the version-control export, so it needs the edit permission, as Appsmith's git export does. (The file export `/api/v1/applications/export/{id}` needs the export permission, which only ADMIN has, so a Dev could not pull with it.) | Dev (with an Edit grant on the app), Admin, Approver |
| `POST /api/v1/projects-repo/applications/import/{workspaceId}[?applicationId=]` | App JSON (body `application/json`) as a new app, or over the edit copy of `applicationId` (see "Publishing"). Answers `applicationId`, `workspaceId`, `name`, `unconfiguredDatasources`, `unchanged`. App JSON identical to the app's current export is not imported (`unchanged: true`): an import always writes new layout ids, which the approval fingerprint counts as an edit. | Dev, Admin (the normal import permissions: over an app, a Dev needs an Edit grant on it; a new app gives the importing Dev one; the Approver filter refuses it) |

`EditCopyJson` keeps every secret on the instance: the export never carries the published copy, the stored datasource
structure, decrypted fields or modified-resources bookkeeping; each datasource is reduced to `name` and `pluginId`; a
query that uses a saved datasource refers to it without its configuration; an API query with its own URL keeps the
URL, headers and parameters (they are the query) but never its authentication or SSH proxy settings. In every query
the literal values of secret headers and parameters are replaced by `__secret_kept_on_server__`
(`EditCopyJson.STRIPPED_VALUE`): `actionConfiguration.headers`, `.queryParameters` and the query string of `.path`, and
the own datasource's `headers`, `queryParameters` and the query string of its `url`. A name is secret when it contains
(case-insensitive) `authorization`, `cookie`, `token`, `secret`, `key`, `password`, `passwd`, `signature` or
`credential`, or is `auth`; values with a `{{ }}` binding and empty values stay. The MCP tool strips the datasource
details again (not the secret values: the server's export already has none), and cab-sync writes datasources as name
and plugin only. `McpAllowlistExtensions` allows exactly these two routes (prefix `/api/v1/projects-repo`, `import`
reserved as a segment).

Round trip: the import replaces a query's header and parameter lists and URL whole (authentication is kept, since the
importer copies only non-null fields), so the marker itself would be stored. `ProjectsRepoService.importEditCopy`
therefore first puts the stored value back (`EditCopyJson.putBackStoredValues`) for every marker, matching the query by
`gitSyncId` and the header or parameter by name (case-insensitive) and position among those of that name, and compares
for "unchanged" with the secrets in place. A marker with no stored value (a new or copied query, a renamed header, a
new app) refuses the import (400) with the list of those values. A stored value is only put back when the query still
sends its requests where the stored one does (`EditCopyJson.target`): the same saved datasource (its `id`, the name
in the file) and the same scheme, host and port (the authority, user info included, letter case ignored) of the own
datasource's `url` followed by `.path`, so a path starting `.evil.net/`, `@evil.net/` or `:8443/` counts as moved.
An authority with a `{{ }}` binding is the same only when its text is identical. Otherwise the import is refused (400)
with the query and header/parameter names, and nothing is saved: whoever edits the file (a Dev, who can't read the
secrets) could otherwise point the query at their own host and receive them. The fix is to write the value again (a
literal or a binding) on purpose. Only this route does it: a marker in a file imported
through `/api/v1/applications/import` is stored as it is. Not stripped: request bodies and form data, and secret
values in any other field.

The MCP server of a fork-uat instance runs from `scripts/fork-uat` (`up.ps1`, `127.0.0.1:<Port+6>`, proxied at
`/mcp`), once MCP is enabled on the instance.

A file that differs from the edit copy only in `exportedApplication.tags` is not imported: only the tags are written
(as a Dev with Edit or an Admin, clamped like an import), so a tag change pushed through cab-sync keeps a submitted or
approved app as it is; the import answers `tagsOnly: true`. No tags and an empty list count as the same. cab-sync
writes no `tags` key for an app without tags and sends `tags: []` when `application.json` has none, so deleting the
key clears them (the importer keeps stored values a file leaves out).

## App tags and catalog

Server code: `app/server/appsmith-server/src/main/java/com/appsmith/server/catalog/`. Client: the home page (see
"The home page" below) and the Tags field in the app settings.

**Tags** (`Application.tags`, a list) are free text. Every write normalises them (`ApplicationTags`): trimmed,
lower-cased, inner white space collapsed to one space, empty tags and repeats dropped, first-seen order kept; at most
10 tags of at most 30 characters (code points) each. The tags endpoint refuses a list over the limits (400); every
other path (the domain setter, an import, a cab-sync push) clamps instead, as the description does. Tags travel with
the app JSON (`exportedApplication.tags`; JSON views Public and Git), so they reach the projects repo and come back
through import; a duplicated app keeps them.

**Who sets them, and the read-only editor.** Tags are app metadata, not app content: a Dev with Edit on the app or an
Admin sets them through `PUT /api/v1/app-tags/applications/{applicationId}` (body `{"tags": [...]}`, the full list;
`[]` clears), also while the editor is read-only, and through MCP (`set_app_tags`). The route has its own family so
the editor lock on `PUT /api/v1/applications/{id}` does not cover it and the MCP allowlist can list it as an
extension (the core `/api/v1/applications` rules stay as they are). Approvers (their filter refuses every non-GET)
and Users (role check, and `RoleGate`) cannot. Only `tags` and `updatedAt` are written, and the approval fingerprint
does not read `tags`, so a tag change never cancels a pending request and never takes a live app offline. With the
lock off, `PUT /api/v1/applications/{id}` also takes `tags` (clamped by the setter).

| Endpoint | What |
| --- | --- |
| `GET /api/v1/applications/catalog?search=&tags=&tagMode=any\|all&sort=name\|lastDeployedAt\|updatedAt\|createdAt&order=asc\|desc&page=&size=` | One page of the apps the caller can read: `{items, total, page, size}`. Every filter, the sort and the paging are part of the repository query with the caller's read permission, so grants decide what each user sees and a User gets only live apps (`CustomApplicationRepositoryImpl`). `search` (at most 100 characters) matches name, description or a tag, case-insensitively and literally (regex characters are quoted); it matches part of a tag (`search=fin` finds an app tagged `finance`). `tags` (`tags=a,b` or repeated, normalised, at most 10) matches whole tags only (`tags=fin` does not find `finance`): `any` (default) or `all`. `sort` defaults to `name` ascending; the dates default to newest first; the id breaks ties. `page` starts at 0; `size` 1 to 100 (default 20). Anything else is refused (400). |
| `GET /api/v1/applications/tags` | Every tag on an app the caller can read, sorted, for the filter chips |
| `PUT /api/v1/app-tags/applications/{applicationId}` | Set the tags, see above; answers `{applicationId, tags}` |

A catalog item: `id`, `baseId`, `workspaceId`, `name`, `description`, `slug`, `uniqueSlug` (static URL, when on),
`icon`, `color`, `tags`, `lastDeployedAt`, `updatedAt` (ISO strings), `defaultPageId` and `defaultPageSlug` (the live
default page of a live app, the edit copy's otherwise; the view URL is `/app/<slug>/<defaultPageSlug>-<defaultPageId>`),
`approvalStatus` (the stored status, one query per page; a later read of the approval state may still cancel it;
null for Users) and `canEdit` (the caller is a Dev or Admin and holds the edit permission on the app; for a Dev, an
Edit grant). Name sort is by stored text (upper case before lower case); there is no collation in the query API.

Indexes: `application_tags_deleted` (`tags`, `deletedAt`; `Migration077AddApplicationTagsIndex`); name search and
sort use the existing `name` + `deletedAt` index (Migration037).

### The home page

The home page is a flat catalog of the apps the user can open (`src/ee/pages/Applications/Catalog/CatalogSection.tsx`,
in place of the workspace's app list): a search box (the server's `search`, sent 300 ms after the typing stops), tag
chips from `GET /api/v1/applications/tags` (several can be selected; with two or more, "Any tag" / "All tags" sets
`tagMode`), a sort (name, last published, last updated) and pages of 24 cards. Every change asks the server again; the
query and the current page live in the `ui.catalog` store (`src/ee/sagas/catalogSagas.ts`,
`src/ee/reducers/uiReducers/catalogReducer.ts`, `src/ee/api/CatalogApi.ts`); a rename or delete from a card reloads
it. The header's search box is hidden with the workspace UI (it searched workspaces and apps on the client).

- The cards are upstream's `ApplicationCard`, fed from the catalog item (`src/ee/utils/catalog.ts`): Edit only when
  the item's `canEdit` is true; otherwise Launch only. The tags show under each card.
- No "Create new" menu (new app, import) for anyone, and `?openImportModal=true` opens nothing
  (`isAppCreationUiHidden`, see "Hidden features"): apps are created and imported through MCP and the projects repo.
  The server's create and import APIs stay. (Before, it was for Devs and Admins only, `WorkspaceAction.tsx`.)
- The app name is plain text on the card, in its menu, in the editor header (no "Rename") and in the app settings
  (read-only field) (`isAppRenameUiHidden`); MCP and import rename apps.
- Admins get Delete and an **Access** item in each card's menu: who holds View or Edit on the app (a team or a
  single Dev or User), change the level, remove it, or give it to someone else
  (`src/ee/pages/Applications/Catalog/AppAccessModal.tsx`, `src/ee/api/TeamsApi.ts`). Admins and Approvers are not
  offered there, since they see every app through their role.
- **Tags in the app settings** (General, `src/ee/pages/AppSettings/AppTagsSetting.tsx`): a Dev with Edit on the app
  or an Admin adds a tag (Enter or comma; normalised and limited as the server does) or removes one, also while the
  editor is read-only. Others see the tags only.
- **An editor URL without Edit:** the edit-mode load answers 404 for a Dev without an Edit grant on the app
  (`isEditModeRefused` in `src/sagas/InitSagas.ts`), so the Dev gets the toast "You don't have edit access to this
  app. Ask an Admin for Edit access." and the same page in view mode (or the not-found page when they can't view the
  app either), as a User does. The editor's init goes on as the viewer's: the init watcher takes the leading init
  only (`takeLeading`), so the init the viewer dispatches when it mounts would be dropped while the editor's runs.

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
  `areAppsmithLinksHidden`, `isAppCreationUiHidden`, `isAppRenameUiHidden`, `isViewerEditButtonHidden`,
  `isEmbedUiHidden`, `areUpsellsHidden`, always true) and, for Git, `ce/selectors/builtInGitSelectors.ts`
  (`getIsBuiltInGitEnabled`). Every hidden entry point checks one of them, with a `Fork:` comment.

### UI only: create, import, rename, embed, the viewer's pencil, upsells

These are hidden in the UI only; the server APIs stay (MCP, cab-sync and import use them).

- Create new and Import on the home page, and app rename: see "The home page".
- The app viewer has no pencil ("Edit app") button (`PrimaryCTA.tsx`); in the editor's own preview it stays, to leave
  the preview.
- No embedding: the app settings have no "Share & embed" section and the editor's Share dialog no Embed tab. The app
  URL and the public toggle stay in the Share dialog.
- No Business or Enterprise upsell (`areUpsellsHidden`). Upstream marks a paid page or field with
  `isFeatureEnabled: false`; `ConfigFactory.register` drops those pages and fields
  (`ee/pages/AdminSettings/config/upsells.ts`), and their URLs lead to General. Gone: the Audit logs, Access control,
  Provisioning, Branding and User settings pages, the Connection pool size field, the SAML and OIDC cards on
  Authentication, the locked "Staging" environment (bottom bar and datasource editor, `ee/selectors/rampSelectors.tsx`
  turns off every product ramp), the premium "upcoming integrations" and their star badge. A query whose plugin is not
  installed no longer says "Upgrade your plan". Git's upsells only show with built-in Git switched on.

### Workspaces: one hidden workspace

There is one workspace, "Apps" (see "Roles"), and users never see it as a workspace.

- Hidden: the workspace list and "create workspace" on the home page (also on mobile), the workspace menu (settings,
  rename, delete, leave, members, invite), the member avatars and the invite button, the `/workspace/...` settings and
  members pages, the header's search box (it searched workspaces and apps; the home page catalog has its own search),
  "Fork" on the app card (it copies an app into a workspace
  picked from a list; "Duplicate" stays), and the invite form in the editor's and the viewer's Share dialog (the app URL
  and the public toggle stay; the dialog is titled "Share application").
- The home page shows the apps directly, as a catalog (see "App tags and catalog"), under the default workspace picked
  by `defaultWorkspaceId` from `/users/me`; there is no "Create new" menu (see "The home page").
  Favourites are not listed separately.
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

## Branding

Users see the product as **CreativeAppBuilder**, an internal tool for dashboards and reports at Creative. Only what
users see is renamed: package and class names, env vars (`APPSMITH_*`), headers (`X-Appsmith-*`, `X-APPSMITH-SIGNATURE`),
Docker images, the MCP tool names, code identifiers (`appsmith.store`, `appsmith.model`, ...), the import/export JSON
format and the Appsmith licence notices keep the Appsmith name.

### Brand assets (swap the logo by replacing files)

Every brand file is in `app/client/public/brand/`, served at `/brand/...`. To change the logo, replace the files and
keep their names; nothing else needs to change. The Creative logos come from the Premium Store project
(`PremiumStoreRevamp/Web/...`); the square mark's PNGs were added there on 2026-10-22.

| File | What | Size | Used by |
| --- | --- | --- | --- |
| `creative-logo-colour.svg` | "CREATIVE" wordmark, teal `#00aab5` letters, orange `#f7941e` A and bar. Made from the mono SVG's paths, coloured as the colour PNG (no colour SVG exists). | 276.4 x 50 viewBox | Not referenced (light backgrounds use the mono wordmark since #91) |
| `creative-logo-white.svg` | Official white wordmark (`landingpages/revamp-temp/images/logo-creative-white.svg`), copied as is. It has `width`/`height` but no `viewBox`; Chromium-based browsers scale it anyway. | 93.3 x 16.9 | Dark backgrounds (`components/BrandLogo` with `onDark`, or the theme mode `DARK`) |
| `creative-logo-mono.svg` | Original grey `#4c4c4d` wordmark (`content2/Images/common/logo_creative.svg`), source of the colour SVG's paths | 276.4 x 50 | Light backgrounds: login, signup, home and settings header, error pages, `404.html` |
| `creative-logo-colour.png` | Original colour PNG (`content2/contactus/images/creative_logo.png`), the colour reference | 171 x 31 | Not referenced |
| `favicon-lightmode.png` | Square "A" mark (triangle outline over a bar), dark grey `#3c3c3c` on transparent: **source** of the light-mode icons | 512 x 512 | `fork-brand-icons.js` |
| `favicon-darkmode.png` | Same mark, light grey `#efefef`: **source** of the dark-mode icons | 512 x 512 | `fork-brand-icons.js` |
| `icon.svg`, `icon-darkmode.svg` | The mark traced from the PNGs (same framing; a render differs from the PNG by 0.1 % alpha on average) | 512 viewBox | Editor header, top left (dark grey, or light grey in the `DARK` theme mode); web manifest |
| `favicon.ico`, `favicon-darkmode.ico` | PNG-in-ICO from the two source PNGs | 16, 32, 48 px | Browser tab: `index.html` and `404.html` pick one by `prefers-color-scheme` (`<link rel="icon" media=...>`); `useBrandingTheme` adds a link only for a custom favicon |
| `icon-192.png`, `icon-512.png` | From `favicon-lightmode.png`, transparent | 192, 512 px | Web manifest |
| `apple-touch-icon.png` | From `favicon-lightmode.png`, on white | 180 px | `index.html` |

After replacing the source PNGs, regenerate the ICO and PNG sizes with `node app/client/scripts/fork-brand-icons.js`
(renders with the installed Microsoft Edge through the client's Playwright) and replace the two SVGs, then commit.

The wordmark carries no product name, so `components/BrandLogo.tsx` shows "App Builder" beside it, after a thin
divider (the mono wordmark on light backgrounds, the white one on dark ones; the product UI has no dark mode today, so
every current use is light). Neither part may shrink, so the text is never cut. A custom favicon (organization config
`brandFaviconUrl`; no admin setting sets it in CE) replaces the href of every icon link of `index.html`
(`useBrandingTheme.ts`, `setFavicon`), since a link added after them did not reliably win. The client constants are in `src/constants/Brand.ts` (`PRODUCT_NAME`, the asset URLs); the server's
in `constants/Brand.java`. `utils/BrandingUtils.ts` points the default logo and favicon (the organization config's
`brandLogoUrl` / `brandFaviconUrl`) at these files.

### What says CreativeAppBuilder

- Browser tab titles (`ce/utils/index.ts`, the editor and custom widget builder titles), `index.html` title and
  description, `manifest.json` (`name` CreativeAppBuilder, `short_name` App Builder, description, icons),
  `404.html`.
- Messages: `ee/constants/messages.ts` overrides every CE message that names Appsmith on a reachable screen (error
  pages, version label, toasts, admin settings, embed settings); `ce/constants/messages.ts` is unchanged. Admin
  settings texts in `ce/pages/AdminSettings/config/configuration.tsx`, `instanceSettings.ts`.
- Server: the default instance name (`Appsmith.DEFAULT_INSTANCE_NAME`, used on a fresh install; an existing instance
  keeps its stored name, editable in Admin settings), the email sender name, subjects, link texts and HTML templates,
  the SMTP test email, and the `AppsmithError` messages users can see ("contact your administrator" instead of
  Appsmith support). `approvedErrorMessages.ts` accepts both names. Covered by `constants/BrandTest`.
- The app viewer shows no "Built on Appsmith" badge (`ee/selectors/organizationSelectors.ts`, `getHideWatermark`), and
  Admin settings no longer offers the watermark switch.

### No links to Appsmith

`areAppsmithLinksHidden()` (`ce/utils/forkHiddenFeatures.ts`, always true) hides every link a user can see to an
Appsmith host: docs, community, Discord, Appsmith's GitHub, the blog, support mail. `isAppsmithLink(url)` /
`withoutAppsmithLink(url)` in the same file tell such URLs apart. Nothing is re-pointed to another site. Where:

- `ce/configs/index.ts`: `disableHelpIcon` is on, so the editor Help button, the home page "?" menu (Documentation,
  What's new, version), the editor's Help submenu and the mobile sidebar's Discord and Documentation links are gone.
- Autocomplete: the `[docs]` link in the hint popup and Ctrl-I tooltip, and Ctrl-O (`ternDocTooltip.tsx`,
  `CodemirrorTernService.ts`), for the ~135 `!url` entries of the widgets and `EntityDefinitions`.
- `pluginsReducer.ts` drops plugin `documentationLink`s on Appsmith hosts (the query toolbar's "Documentation").
- `openDoc()` (`constants/DocumentationLinks.ts`) opens nothing for an Appsmith URL; its buttons are hidden: debugger
  "Open documentation", the property pane's "Learn more" (`ConnectDataCTA`), query settings' docs link, the Appsmith
  AI deprecation callout.
- Admin settings: "How to configure?" callouts (email, Google, GitHub), "Learn more" on callouts (`FormGroup/group.tsx`),
  the Base URL, Google Maps and frame-ancestors help links (`Radio.tsx` link now optional).
- One-offs: error pages' "Contact us on discord", the omnibar's Discord line, the security confirm modals' "Learn
  more", widget callout links (Chart's FusionCharts blog post), the forgot-password callout, the SMTP test toast's
  "Troubleshoot", the JSON form's "Learn more" toast, the static URL "?" button, the JS library installer's links, the
  Map widget's docs link, the custom widget "Read more" links, builder Documentation button and console text.

## Admin settings

The settings pages share one shell (`ee/pages/AdminSettings/SettingsShell.tsx`): the home page header, a calm left nav
(small uppercase groups, no icons, no tier badges, the current page in teal; a select above the page below 860px),
each page with a title, a subtitle and its fields in a card (label left, field and help right), and a sticky footer
with Reset and Save, both disabled until something changes. Save says "Save & Restart" and the footer "Saving restarts
the server" only when the page's settings need a restart; organization settings (such as the instance name) save
without one ("Save Changes"). Styles: `pages/AdminSettings/LeftPane.tsx`, `components.tsx`, `SaveSettings.tsx`,
`SettingsForm.tsx`, `Profile/`.

- **General** holds only the instance name (moved from Instance settings; `ee/pages/AdminSettings/config/general.tsx`,
  `instanceSettings.tsx`). "Anonymous usage data" is gone (telemetry is removed anyway); the server still reads
  `APPSMITH_DISABLE_TELEMETRY`, with no effect.
- The paid-plan pages and fields are not offered (see "Hidden features").

## Build and deploy

The fork builds its own image from source. Docker is the only tool needed on the build machine (no JDK, Maven,
Node or yarn on the host), and nothing is pulled from Appsmith: base layers are official Docker Hub images
(`ubuntu`, `redis`, `caddy`, `node`, `maven` with Temurin 25, and `postgres:16` in the compose file) plus the
PostgreSQL apt, Adoptium, Node.js, npm and Maven Central downloads the upstream base already used. The base has no
MongoDB server, shell or tools (upstream also built mongo-tools from GitHub).

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
5. `deploy/fork/docker-compose.yml` runs the image with a `postgres:16` service as the internal database (volume
   `creative-app-builder-postgres`, no published port; a commented `ports` line binds it to `127.0.0.1` if needed)
   and the image's embedded Redis, in a named volume `creative-app-builder-stacks`. It needs
   `APPSMITH_POSTGRES_PASSWORD` (letters and digits; it goes into the database URL) and refuses to start without it:
   `APPSMITH_POSTGRES_PASSWORD=... docker compose -f deploy/fork/docker-compose.yml up -d`, or `--env-file`. The
   port binds to `127.0.0.1:8000` by default; override with `APPSMITH_HTTP_PORT`, `APPSMITH_BIND_ADDRESS`,
   `APPSMITH_CONTAINER_NAME`, `APPSMITH_VOLUME_NAME`, `APPSMITH_POSTGRES_CONTAINER_NAME`,
   `APPSMITH_POSTGRES_VOLUME_NAME`, `APPSMITH_IMAGE`. `pull_policy: never`, so compose never tries a registry. On
   first start the container writes `stacks/configuration/docker.env` with generated Redis and encryption secrets:
   keep the volume.
   Without `APPSMITH_DB_URL` (a plain `docker run` of the image), the entrypoint runs an embedded PostgreSQL 16 in
   the stacks volume (`data/postgres/main`), and `docker.env` gets `APPSMITH_DB_URL=postgresql://appsmith:<generated
   password>@localhost:5432/appsmith`. The embedded one runs only when the URL is local; `run-java.sh` creates the
   `appsmith` user, schema and `pg_trgm` there before the server starts, and Flyway creates the tables. A
   `mongodb://` URL (a volume first started by a MongoDB image) stops the container with what to do: start on a
   fresh volume, or set a `postgresql://` URL. `pg-upgrade.sh` upgrades an older embedded data folder (14, from
   earlier images) to 16. `appsmithctl backup` / `restore` still run `mongodump` / `mongorestore` (in
   `app/client/packages/rts/src/ctl`), so they don't work on this image yet; use `pg_dump` on the database.
   Checked on `creative-app-builder:fork-postgres` (October 2026), each on a fresh volume: `docker run` with the
   embedded PostgreSQL 16 (Flyway V1–V7, `/api/v1/health` 200 about 25 s after start, first-admin signup and login,
   login again after `docker restart`), the compose file with its `postgres` service (same checks; no PostgreSQL in
   the app container, only `127.0.0.1:<port>` published), and a `mongodb://` URL (the container stops with the
   message, exit code 1).
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

- **Static links** (opened by the user, nothing loads them at runtime): the `docs.appsmith.com`, Discord, community
  and GitHub URLs are still in the code, but no reachable screen shows them since the rebrand (see "Branding", "No
  links to Appsmith"). Left: links inside hidden features (built-in Git, onboarding, templates, cloud-only signup and
  IP whitelist texts), `www.appsmith.com/enterprise` upgrade ramps (see "Upgrade links"), the `more info - <docs URL>`
  comment in the custom widget code templates, the Google Sheets "Learn more" in `AuthMessage.tsx` (proxied OAuth is
  off; `openDoc` opens nothing anyway), and the server's `SCHEMA_MISMATCH_ERROR` (an upgrade-path error for operators).
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
container's network namespace (on the MongoDB image, before the PostgreSQL port): a first boot on an empty volume
(MongoDB init, migrations), the first-admin signup,
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

### Teams and app grants

- New server files: `grants/` (`AppGrantService`, `AppGrantController`, `AppGrantDTO`, `GrantLevel`, `GrantGroups`,
  `TeamService`, `TeamController`, `TeamDTO`), `roles/RoleGate.java`, `roles/RoleGroupReconciler.java`, and the tests
  `grants/AppGrantServiceTest`, `grants/TeamServiceTest`.
- Edited upstream files (`Fork:` comments): `repositories/ce/CacheableRepositoryHelperCEImpl.java` and
  `CacheableRepositoryHelperCE.java` (teams and personal grant groups count among a user's groups; the user's grant
  groups are cached for `RoleGate`), `repositories/ce/CustomPermissionGroupRepositoryCEImpl.java` (clearing a user's
  cached groups also clears their cached grant groups), `repositories/ce/BaseAppsmithRepositoryCEImpl.java` (the `RoleGate` hook
  where a permission check resolves the caller's groups), `applications/base/ApplicationServiceImpl.java` (app name
  check ignores permissions), `solutions/WorkspacePermissionImpl.java` (creating an app needs the create permission
  on the workspace, not "manage every app").
- Edited fork files: `acl/PolicyGenerator.java` (creating apps no longer implies managing them; page and git rights
  come from managing every app; executing the workspace's datasources executes every datasource),
  `roles/InstanceRole.java` (DEV and USER lose the workspace app permissions), `roles/DefaultWorkspaceService.java`
  (runs the reconciler), `repositories/CustomApplicationRepositoryImpl.java` (a User's live-only filter covers every
  grant), `services/ApplicationPageServiceImpl.java` (creator Edit grant), and the tests
  `acl/PolicyGeneratorTest`, `roles/InstanceRolePermissionsTest`, `roles/DefaultWorkspaceServiceTest`,
  `approvals/ApplicationApprovalServiceTest`, `projectsrepo/ProjectsRepoServiceTest`.

### App tags and catalog

- New server files: `catalog/` (`ApplicationTags`, `ApplicationCatalogService`, `ApplicationCatalogController`,
  `AppTagsController`, `ApplicationCatalogDTO`), `migrations/db/ce/Migration077AddApplicationTagsIndex.java`, and
  the tests `catalog/ApplicationCatalogTest`, `catalog/ApplicationTagsTest`.
- Edited upstream files (`Fork:` comments): `domains/ce/ApplicationCE.java` (`tags`, its setter, the duplicate keeps
  them), `applications/imports/ApplicationImportServiceImpl.java` (imported tags go through the setter).
- Edited fork files: `constants/Url.java` (`APP_TAGS_URL`), `filters/McpAllowlistExtensions.java`,
  `projectsrepo/ProjectsRepoService.java` (tag-only push, `tagsOnly`), `repositories/CustomApplicationApprovalRepository*`
  (`findAllByApplicationIdIn`), and the tests `projectsrepo/ProjectsRepoServiceTest`,
  `filters/McpAllowlistWebFilterTest`, `editorlock/EditorReadOnlyWebFilterTest`.
- MCP: `packages/mcp/src/ee/extensions/` (`api.ts`, `catalog.ts`, `tools.ts`, `routeContract.ts`: `set_app_tags`),
  the new test `appTags.test.ts`, `projectsRepo.test.ts`, the upstream files `src/app.ts` (`list_applications` tags
  filter, `get_application_context` tags) and `src/builder/capabilities.ts` (their summaries), and `README.md`.
- cab-sync: `src/layout.ts` (canonical tags), `src/commands.ts` (tag-only push message), `src/template.ts`,
  `README.md` and the tests.
- New client files: `src/ee/api/CatalogApi.ts`, `src/ee/api/TeamsApi.ts`, `src/ee/actions/catalogActions.ts`,
  `src/ee/reducers/uiReducers/catalogReducer.ts` (and `.test.ts`), `src/ee/sagas/catalogSagas.ts` (and `.test.ts`),
  `src/ee/selectors/catalogSelectors.ts`, `src/ee/utils/catalog.ts`, `src/ee/pages/Applications/Catalog/`
  (`CatalogSection`, `AppAccessModal` and their tests), `src/ee/pages/AppSettings/AppTagsSetting.tsx` (and test),
  `src/ce/pages/AdminSettings/config/teams.ts`, `src/pages/AdminSettings/Teams/` (page and test).
- Edited upstream client files (`Fork:` comments): `src/ce/constants/ReduxActionConstants.tsx` (catalog actions),
  `src/ce/reducers/index.tsx` and `uiReducers/index.tsx` (`ui.catalog`), `uiReducers/applicationsReducer.tsx`
  (`CURRENT_APPLICATION_TAGS_UPDATE`), `src/entities/Application/types.ts` (`tags`),
  `src/ce/pages/Applications/index.tsx` (the catalog in place of the app list),
  `src/pages/Applications/ApplicationCard.tsx` (`extraMenuItems`), `src/pages/common/SearchBar/EntitySearchBar.tsx`
  (header search hidden), `src/pages/AppIDE/components/AppSettings/components/GeneralSettings.tsx` (Tags), and the test
  `src/ce/pages/Applications/tests/hiddenFeatures.test.tsx`.
- Edited fork files: `src/ce/utils/instanceRoles.ts` (and `.test.ts`), `src/ce/pages/AdminSettings/config/index.ts`,
  `src/ce/pages/Applications/WorkspaceAction.tsx` (and test), `src/ee/sagas/index.tsx`, `src/sagas/InitSagas.ts`
  (and `__tests__/initSagas.test.ts`).

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

### Branding (CreativeAppBuilder)

- New: `app/client/public/brand/` (brand files, see "Branding"), `app/client/scripts/fork-brand-icons.js`; client
  `src/constants/Brand.ts`, `src/components/BrandLogo.tsx` (and test), `src/ee/constants/messages.test.ts`; server
  `constants/Brand.java`, test `constants/BrandTest`.
- Edited fork files: `src/ee/constants/messages.ts` (overrides), `src/ee/selectors/organizationSelectors.tsx`
  (`getHideWatermark`), `src/ce/utils/forkHiddenFeatures.ts` (`areAppsmithLinksHidden`, `isAppsmithLink`,
  `withoutAppsmithLink`), `FORK.md`.
- Edited upstream client files (`Fork:` comments): `public/index.html`, `404.html`, `404.css`, `manifest.json`;
  `src/utils/BrandingUtils.ts`, `src/utils/hooks/useBrandingTheme.ts`, `src/ce/utils/index.ts`, `src/ce/configs/index.ts`
  (and `.test.ts`), `src/ce/constants/approvedErrorMessages.ts`, `src/ce/pages/AdminSettings/config/authentication.tsx`,
  `configuration.tsx`, `general.tsx`, `instanceSettings.ts`, `src/ce/sagas/SuperUserSagas.tsx`,
  `src/pages/AdminSettings/config/email.ts`, `FormGroup/group.tsx`, `FormGroup/Radio.tsx`,
  `src/pages/UserAuth/Container.tsx`, `index.tsx`, `ForgotPassword.tsx`, `src/pages/common/ErrorPageHeader.tsx`,
  `SearchBar/EntitySearchBar.tsx`, `ErrorPages/GenericError.tsx`, `ErrorPages/ClientError.tsx`,
  `src/pages/Editor/AppsmithLink.tsx`, `src/pages/AppIDE/AppIDE.tsx`,
  `src/pages/AppIDE/components/AppSettings/components/GeneralSettings.tsx`, `StaticURLConfirmationModal.tsx`,
  `src/pages/AppViewer/AppViewerPageContainer.tsx`, `src/pages/Editor/CustomWidgetBuilder/index.tsx`,
  `Editor/References/help.tsx`, `Preview/Debugger/helpDropdown.tsx`,
  `src/pages/Editor/DataSourceEditor/RestAPIDatasourceForm.tsx`, `src/pages/Editor/Explorer/Libraries/Installer.tsx`,
  `src/pages/Editor/PropertyPane/ConnectDataCTA.tsx`, `helpers.tsx`,
  `src/PluginActionEditor/components/PluginActionSettings/index.tsx`,
  `src/components/editorComponents/AppsmithAIDeprecationCallout.tsx` (and test), `Debugger/ContextualMenu.tsx`,
  `GlobalSearch/ResultsNotFound.tsx`, `src/components/formControls/SwitchWithConfirmationControl.tsx`,
  `src/constants/DocumentationLinks.ts`, `src/reducers/entityReducers/pluginsReducer.ts`,
  `src/sagas/ActionExecution/errorUtils.ts` (and test, and `src/api/__tests__/apiFailureResponseInterceptors.test.ts`),
  `src/utils/autocomplete/CodemirrorTernService.ts`, `ternDocTooltip.tsx`, the widgets `ChartWidget`
  (`propertyConfig.ts`), `CustomWidget`, `JSONFormWidget`, `MapWidget`, `TableWidget`, `TableWidgetV2`,
  `wds/WDSCustomWidget` (`contentConfig.tsx`), `wds/WDSTableWidget` (`autocompleteConfig.ts`).
- Edited upstream server files: `configurations/EmailConfig.java`, `constants/Appsmith.java`,
  `constants/ce/EmailConstantsCE.java`, `exceptions/AppsmithError.java`, `AppsmithErrorCode.java`,
  `helpers/ce/EmailServiceHelperCEImpl.java`, `layouts/UpdateLayoutServiceCEImpl.java`, `notifications/EmailSender.java`,
  `solutions/ce/DatasourceStructureSolutionCEImpl.java`, `solutions/ce/EnvManagerCEImpl.java`, the email templates in
  `resources/email/ce/`, test `helpers/ce/EmailServiceHelperCETest.java`; plugins `restApiPlugin` and `graphqlPlugin`
  `form.json` (signature header label).

### UI/UX round 1 (#91: upsells, logo, create/import/rename/embed, settings shell)

- New client files: `src/ee/pages/AdminSettings/SettingsShell.tsx`, `src/ee/pages/AdminSettings/config/upsells.ts`
  (and test), `src/ee/components/DatasourceManagedNote.tsx`, tests `src/pages/AdminSettings/SaveSettings.test.tsx`,
  `src/utils/hooks/useBrandingTheme.test.ts`, `src/ce/constants/approvedErrorMessages.test.ts`,
  `src/pages/AppIDE/layouts/routers/MainPane/MainPane.test.tsx`.
- Edited fork files: `src/ce/utils/forkHiddenFeatures.ts` (and test), `src/constants/Brand.ts`,
  `src/components/BrandLogo.tsx` (and test), `src/ee/constants/messages.ts` (`DATA_PANE_TITLE`, `PLUGIN_NOT_INSTALLED`),
  `src/ee/pages/AdminSettings/config/general.tsx`, `instanceSettings.tsx`, `src/ee/selectors/rampSelectors.tsx`.
- Edited upstream client files (`Fork:` comments): `public/404.html`; `src/ce/pages/AdminSettings/config/authentication.tsx`,
  `src/ce/pages/Applications/WorkspaceAction.tsx` (and test), `src/ce/pages/Applications/index.tsx`,
  `src/components/editorComponents/PremiumFeatureTag.tsx`, `src/pages/AdminSettings/` (`index.tsx`, `LeftPane.tsx`,
  `Main.tsx`, `SettingsForm.tsx`, `SaveSettings.tsx`, `components.tsx`, `config/ConfigFactory.ts`, `FormGroup/Common.tsx`,
  `Profile/index.tsx`, `Profile/StyledComponents.tsx`), `src/pages/AppIDE/components/AppSettings/AppSettings.tsx`,
  `components/GeneralSettings.tsx`, `src/pages/AppIDE/layouts/components/Header/index.tsx`,
  `src/pages/AppIDE/layouts/routers/MainPane/MainPane.tsx`, `src/pages/AppViewer/PrimaryCTA.tsx` (and test),
  `src/pages/Applications/ApplicationCard.tsx` (and test), `src/pages/Editor/DataSidePane/DataSidePane.tsx` (and test),
  `src/pages/Editor/DataSourceEditor/DSFormHeader.tsx`, `src/pages/Editor/EditorName/useNavigationMenuData.ts`,
  `src/pages/Editor/IntegrationEditor/PremiumDatasources/Constants.ts`, `src/pages/common/SearchBar/EntitySearchBar.tsx`,
  `src/utils/hooks/useBrandingTheme.ts`; Cypress `AdminSettings/Email_settings_Spec.ts` (rebranded texts).
- Edited upstream server files (plugin messages without Appsmith's name or support, `Fork:` comments):
  `appsmith-interfaces` `AppsmithPluginError`, `AppsmithPluginErrorCode`, `BasePluginErrorMessages`, `PluginUtils`,
  `ApiKeyAuthentication`, `FilterDataServiceCE`; the error messages of the S3, ArangoDB, Databricks, Firestore, Google
  Sheets, GraphQL, Mongo, MSSQL, MySQL, Oracle, Postgres, Redis, Redshift and Snowflake plugins; tests that expect
  them (S3 characterization JSON, Mongo, MySQL, Snowflake, server `DatasourceServiceTest`, `LayoutActionServiceTest`).

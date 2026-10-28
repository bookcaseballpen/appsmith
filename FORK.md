# Fork notes

This repository is a permanent fork of Appsmith CE. Fork logic lives in the non-CE `*Impl` subclasses and in new
files; CE files are edited as little as possible.

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

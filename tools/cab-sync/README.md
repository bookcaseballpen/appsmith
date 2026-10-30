# cab-sync

Moves CreativeAppBuilder apps between a **projects repo** (plain files in git, where AI agents and people edit them)
and an instance (where they run, are tested and are approved). The instance's editor is read-only; this is how apps
change.

```
cab-sync init <dir> [--force]          write the projects repo template (README.md, CLAUDE.md, .gitignore, .gitattributes)
cab-sync pull <app> [--repo <dir>]     the app's edit copy into <repo>/apps/<app-slug>/ (or into <app> when it is an app folder)
cab-sync push <app-dir>                the folder into the app's edit copy; creates the app if there is none with its name
cab-sync diff <app-dir>                the files that differ from the instance's edit copy (exit code 1 when any do)
cab-sync submit <app-dir> [--note ..]  submit the app for approval; an Approver approves it in the app
```

Workflow: `pull` → edit the files → `push` → test in the editor's preview → `submit` → a human approves in the app.
A push replaces only the app's edit copy: users keep seeing the approved version until the next release is approved,
and any pending request is cancelled (submit again). A push of a folder that matches the edit copy (`diff` finds
nothing) imports nothing, so it leaves an approved or submitted app as it is.

## Running it

Node.js 24 or later runs the TypeScript sources directly; there is nothing to build or install:

```sh
export CAB_URL=http://localhost:8880         # the instance; the MCP endpoint is $CAB_URL/mcp
export CAB_TOKEN=mcp_...                     # your MCP key: Appsmith > profile > MCP keys
export CAB_WORKSPACE=Apps                    # only when you can see more than one workspace
node tools/cab-sync/src/cli.ts pull "Orders Admin" --repo ~/cab-apps
```

`CAB_TOKEN` is read from the environment only and never written anywhere. An app is found by its name (as in
`application.json`), slug or id in the workspace.

## How it talks to the instance

cab-sync is an MCP client of the instance's MCP server (Streamable HTTP, `src/mcp.ts`), with the user's own `mcp_`
key as the bearer token. It calls `list_workspaces`, `list_applications`, `export_application`,
`import_application` and `submit_for_approval`. The Appsmith server accepts an `mcp_` key only together with the
internal secret that only the MCP server holds (`McpTokenAuthentication`), so cab-sync never has, needs or weakens
it, and an MCP key stays confined to the MCP allowlist (`McpAllowlistWebFilter` / `McpAllowlistExtensions`). Approving
is not an MCP tool: a person approves in the app.

## Files of an app (`apps/<app-slug>/`)

| File | Holds |
| --- | --- |
| `application.json` | The app (edit copy): name, page order, layout, navigation and theme settings, schema versions |
| `theme.json` | The edit-mode theme |
| `datasources.json` | The datasources the app uses, by `name` and `pluginId` only |
| `js-libraries.json` | Custom JS libraries (only if any) |
| `pages/<Page>/page.json` | The page, without its widgets |
| `pages/<Page>/widgets/<Widget>.json` | One widget; `children` names its child widgets in order |
| `pages/<Page>/queries/<Query>.query.json` | A query's settings |
| `pages/<Page>/queries/<Query>.sql` / `.json` / `.txt` | Its body (`.sql` for SQL plugins, `.json` when the body is JSON) |
| `pages/<Page>/jsobjects/<JS>.jsobject.json` | A JS object's settings and its functions' settings |
| `pages/<Page>/jsobjects/<JS>.js` | Its code |

The output is deterministic: keys sorted, two-space JSON with a final newline, datasources and functions sorted by
name, bodies byte for byte. Left out: the published copy, deleted pages/queries/JS objects, permissions, timestamps
and "saved by" fields, the datasources' configuration, and a JS function's code (it is in the `.js`; Appsmith's own
git format does the same). Entities keep their `gitSyncId`, which the import uses to match them with the app's
existing pages, queries and JS objects. Names become file names with characters other than letters, digits, space,
`_ - . ( )` percent-encoded; names that differ only in letter case are refused (they would clash on Windows and
macOS). On push, a query's or JS object's page is the folder it is in.

`push` refuses files that are not part of this layout, a widget no parent lists, and two body files for one query, so
a typo fails loudly instead of being dropped.

## Secrets

No secret leaves the instance; the server strips them before cab-sync sees the export (`EditCopyJson` in
`app/server`, see FORK.md "Projects repo"):

- datasources: only `name` and `pluginId`; a query on a saved datasource has no datasource configuration;
- a query with its own URL (an API query without a saved datasource): no `authentication` or SSH proxy settings;
- in every query, the **literal values** of secret headers and parameters become `__secret_kept_on_server__`: in
  `actionConfiguration.headers`, `.queryParameters` and the query string of `.path`, and in the own datasource's
  `headers`, `queryParameters` and the query string of its `url`. A name is secret when it contains (ignoring case)
  `authorization`, `cookie`, `token`, `secret`, `key`, `password`, `passwd`, `signature` or `credential`, or is
  `auth`. Values with a `{{ }}` binding, and empty values, stay as they are.

On push the server puts the stored value back for every `__secret_kept_on_server__`, matching the query by
`gitSyncId` and the header or parameter by name (letter case ignored) and position among those of that name, so a
pull -> push round trip keeps the secrets. A marker it cannot match (a new or copied query, a renamed header, a new app)
refuses the push with the list of values; write a `{{ }}` binding there instead. A real value written in the file
replaces the stored one (and then sits in the repo), so don't.

The stored value only goes back while the query still points where it did: the same saved datasource, and the same
scheme, host and port in its URL (the own datasource's `url` followed by `.path`; a `{{ }}` binding there must be
unchanged). Changing the path after the host is fine. If you move a query with markers to another host, port, scheme
or datasource, the push is refused with the query and header/parameter names: the secret is not sent to the new
target unless you write its value (a binding, or a literal) there on purpose.

## Development

```sh
npm ci                 # TypeScript and @types/node (from package-lock.json), for the type check only
npm run check-types    # tsc
npm test               # node --test (layout round trip, CLI against a fake MCP server)
```

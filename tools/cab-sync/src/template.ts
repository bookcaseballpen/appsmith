import { access, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

// The projects repo template that `cab-sync init <dir>` writes.

const README = `# CreativeAppBuilder apps

The apps of a CreativeAppBuilder instance, as files. AI agents (and people) change them here; the instance only runs
and tests them. One folder per app under \`apps/\`, written by \`cab-sync pull\` and read by \`cab-sync push\`.

## Workflow

1. \`cab-sync pull <app name>\`: the app's edit copy from the instance into \`apps/<app>/\`.
2. Edit the files (see CLAUDE.md for the layout) and commit.
3. \`cab-sync push apps/<app>\`: the files into the app's edit copy on the instance (a new app if there is none with
   that name). Users keep seeing the published version.
4. Test the app in the editor's preview on the instance.
5. \`cab-sync submit apps/<app> --note "what changed"\`: submit it for approval.
6. An Approver approves it in the instance; only then do users see the new version.

\`cab-sync diff apps/<app>\` lists the files that differ between this repo and the instance's edit copy.

## Setup

- \`CAB_URL\`: the instance, e.g. \`http://localhost:8880\`.
- \`CAB_TOKEN\`: your MCP key (Appsmith > profile > MCP keys, starts with \`mcp_\`). Keep it in your environment or
  your password manager, never in this repo.
- \`CAB_WORKSPACE\` (optional): the workspace name or id, when you can see more than one.
- Node.js 24 or later. Run \`node <appsmith repo>/tools/cab-sync/src/cli.ts <command>\` (or put a \`cab-sync\` alias
  on your PATH).
`;

const CLAUDE = `# Working on CreativeAppBuilder apps (for AI agents)

This repository holds the apps of a CreativeAppBuilder (Appsmith) instance as files. The editor on the instance is
read-only: every change to an app is made here and moved in with \`cab-sync push\`.

## The loop

1. \`cab-sync pull <app name>\` (or \`cab-sync pull apps/<app>\` to refresh) before you start, so you work on the
   current edit copy.
2. Change the files. Keep the JSON valid; key order and formatting do not matter (cab-sync rewrites them on pull).
3. \`cab-sync push apps/<app>\`, then \`cab-sync pull apps/<app>\` and check with \`git diff\` that the instance took
   your change as you meant it.
4. Ask the developer to test the app in the editor's preview. Do NOT submit on your own.
5. When the developer says it is ready: \`cab-sync submit apps/<app> --note "<one line: what changed>"\`.
   An Approver approves or rejects it in the instance; you cannot approve. Any push after a submit cancels the
   request, so submit again after changing the app.

Users always see the last approved version: a push never changes what they see.

## Layout of apps/<app>/

| File | Holds |
| --- | --- |
| \`application.json\` | The app: name, page order (\`exportedApplication.pages\`), layout, navigation and theme settings |
| \`theme.json\` | The edit-mode theme |
| \`datasources.json\` | The datasources the app uses, by \`name\` and \`pluginId\` only |
| \`js-libraries.json\` | Custom JS libraries (only if the app has some) |
| \`pages/<Page>/page.json\` | The page (name, slug, hidden, layout settings), without its widgets |
| \`pages/<Page>/widgets/<Widget>.json\` | One widget; \`children\` lists its child widgets by name, in order |
| \`pages/<Page>/queries/<Query>.query.json\` | A query's settings (datasource, run behaviour, timeouts, ...) |
| \`pages/<Page>/queries/<Query>.sql\` / \`.json\` / \`.txt\` | The query's body |
| \`pages/<Page>/jsobjects/<JS>.jsobject.json\` | A JS object's settings and its functions' settings |
| \`pages/<Page>/jsobjects/<JS>.js\` | The JS object's code |

File names are the entity names (characters other than letters, digits, space, \`_ - . ( )\` appear as \`%XX\`). The
name inside the file is what counts.

## Rules

- Never write a host, URL with credentials, password, token or any other secret into this repo. Datasources are
  configured on the instance by an Admin; refer to them by name (\`datasource.name\` and \`datasource.id\` in a query are
  both the datasource's name). A new datasource name you invent will not work until an Admin creates it.
- A query's secret header and parameter values (Authorization, Cookie, API keys, tokens, passwords, also in its URL)
  arrive as \`__secret_kept_on_server__\`. Leave that value as it is: the push puts the stored value back. It only works
  in a query the app already has (same \`gitSyncId\`) under the same name; anywhere else the push is refused, so write a
  \`{{ }}\` binding there instead (never the secret itself).
- Keep every \`gitSyncId\`: it is how the instance matches files with its pages, queries and JS objects. For a new
  page, query or JS object, copy an existing file and give it a new, unique \`gitSyncId\` (any unique string, e.g.
  \`<app>_<uuid>\`), a new name, and for a widget a new unique \`widgetId\`.
- Widgets: \`MainContainer\` is the top widget. A widget's \`children\` are names of other widget files in the same page;
  each widget is listed by exactly one parent. Set a new widget's \`parentId\` to its parent's \`widgetId\`. Widget names
  are unique within a page.
- Queries and JS objects belong to the page whose folder they are in (cab-sync sets \`pageId\` on push). To move one,
  move its files.
- A JS object's code is only in its \`.js\` file. Its \`functions\` list in \`.jsobject.json\` holds per-function
  settings such as run on page load; a function you add to the code works without an entry there.
- Bindings (\`{{ ... }}\`) and widget properties work as in Appsmith; when unsure, look at how an existing widget or
  query in the repo does it.
- Do not edit published copies, ids of the instance or anything outside \`apps/\` by hand to "fix" a push error:
  read the error, fix the files, push again.
`;

const GITIGNORE = `node_modules/
.env
.env.*
*.log
.DS_Store
`;

// Byte-exact files: no line-ending conversion, so a query body or JS file reaches the instance exactly as written.
const GITATTRIBUTES = `* -text
`;

export const TEMPLATE_FILES: Readonly<Record<string, string>> = {
  "README.md": README,
  "CLAUDE.md": CLAUDE,
  ".gitignore": GITIGNORE,
  ".gitattributes": GITATTRIBUTES,
  "apps/.gitkeep": "",
};

async function exists(path: string): Promise<boolean> {
  try {
    await access(path);

    return true;
  } catch {
    return false;
  }
}

/** Writes the template into {@code dir}; files that already exist are kept unless {@code force}. */
export async function writeTemplate(
  dir: string,
  force: boolean,
): Promise<{ written: string[]; kept: string[] }> {
  const written: string[] = [];
  const kept: string[] = [];

  for (const [path, content] of Object.entries(TEMPLATE_FILES)) {
    const target = join(dir, path);

    if (!force && (await exists(target))) {
      kept.push(path);
      continue;
    }

    await mkdir(join(target, ".."), { recursive: true });
    await writeFile(target, content, "utf8");
    written.push(path);
  }

  return { written, kept };
}

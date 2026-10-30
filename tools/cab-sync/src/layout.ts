import {
  clone,
  isObject,
  parseJson,
  stableStringify,
  type Json,
  type JsonObject,
} from "./json.ts";

// The projects repo's layout of one app (apps/<app-slug>/), split from and put back into Appsmith's app JSON (the
// format export_application returns and import_application takes):
//
//   application.json                      the app: settings, tags, page order, schema versions (edit copy only)
//   theme.json                            the edit-mode theme
//   datasources.json                      the datasources the app uses, by name and plugin only
//   js-libraries.json                     custom JS libraries (only when the app has some)
//   pages/<Page>/page.json                the page, without its widgets
//   pages/<Page>/widgets/<Widget>.json    one file per widget; "children" lists the child widgets by name, in order
//   pages/<Page>/queries/<Query>.query.json   a query's settings
//   pages/<Page>/queries/<Query>.sql|.json|.txt  its body (.sql for SQL plugins, .json when the body is JSON)
//   pages/<Page>/jsobjects/<JS>.jsobject.json the JS object's settings and its functions' settings
//   pages/<Page>/jsobjects/<JS>.js        its code
//
// Only the edit copy is kept: the published side, deleted entities and fields that change on every save
// (permissions, timestamps, who saved) are left out. Keys are sorted and lists ordered, so the same app always gives
// the same bytes. Entities keep their gitSyncId, which the import uses to match them with the app's existing ones.

export type FileMap = Map<string, string>;

const APPLICATION_FILE = "application.json";
const THEME_FILE = "theme.json";
const DATASOURCES_FILE = "datasources.json";
const JS_LIBRARIES_FILE = "js-libraries.json";
const PAGE_FILE = "page.json";
const QUERY_SUFFIX = ".query.json";
const JS_OBJECT_SUFFIX = ".jsobject.json";
const JS_BODY_SUFFIX = ".js";
const BODY_EXTENSIONS = [".sql", ".json", ".txt"] as const;

// Top-level keys split into their own files.
const SPLIT_KEYS = [
  "pageList",
  "actionList",
  "actionCollectionList",
  "datasourceList",
  "customJSLibList",
  "editModeTheme",
];

// Top-level keys never written: the published copy, datasource details and server bookkeeping.
const DROPPED_KEYS = [
  "publishedTheme",
  "publishedPageOrder",
  "publishedDefaultPageName",
  "publishedLayoutmongoEscapedWidgets",
  "decryptedFields",
  "datasourceConfigurationStructureList",
  "modifiedResources",
];

// The application's published side.
const PUBLISHED_APPLICATION_KEYS = [
  "publishedPages",
  "publishedApplicationDetail",
  "publishedAppLayout",
  "publishedCustomJSLibs",
  "publishedModeThemeId",
  "publishedModeThemeName",
  "lastDeployedAt",
];

// Fields that change without an edit of the app, at any depth outside widgets and bodies.
const VOLATILE_KEYS = new Set([
  "userPermissions",
  "policies",
  "policyMap",
  "createdAt",
  "updatedAt",
  "createdBy",
  "modifiedBy",
  "modifiedAt",
  "lastUpdatedTime",
  "lastEditedAt",
  "lastCommittedAt",
]);

// Plugins whose query body is SQL.
const SQL_PLUGINS = new Set([
  "postgres-plugin",
  "mysql-plugin",
  "mssql-plugin",
  "oracle-plugin",
  "snowflake-plugin",
  "redshift-plugin",
  "databricks-plugin",
  "arangodb-plugin",
]);

// Code-point order: the same on every machine, whatever its locale.
function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export class LayoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LayoutError";
  }
}

function withoutVolatile(value: Json): Json {
  if (Array.isArray(value)) return value.map(withoutVolatile);

  if (isObject(value)) {
    const result: JsonObject = {};

    for (const [key, child] of Object.entries(value)) {
      if (!VOLATILE_KEYS.has(key)) result[key] = withoutVolatile(child);
    }

    return result;
  }

  return value;
}

function object(value: Json | undefined): JsonObject | undefined {
  return isObject(value) ? value : undefined;
}

function text(value: Json | undefined): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function isDeleted(entity: JsonObject, editSide: string): boolean {
  const side = object(entity[editSide]);

  return (
    (entity.deletedAt !== undefined && entity.deletedAt !== null) ||
    (side?.deletedAt !== undefined && side.deletedAt !== null)
  );
}

// Windows forbids these as file names, whatever the extension.
const RESERVED_WINDOWS_NAMES = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

function percentEncode(character: string): string {
  return [...Buffer.from(character, "utf8")]
    .map((byte) => `%${byte.toString(16).toUpperCase().padStart(2, "0")}`)
    .join("");
}

/**
 * A file name for an Appsmith name: letters, digits, space, "_", "-", "." and parentheses stay; anything else is
 * percent-encoded, as are a trailing dot or space and a Windows device name, so every name gives a valid, distinct
 * file name on Windows, macOS and Linux.
 */
export function fileName(name: string): string {
  if (name.length === 0) throw new LayoutError("An entity has an empty name.");

  let encoded = [...name]
    .map((character) =>
      /[A-Za-z0-9 _\-.()]/.test(character)
        ? character
        : percentEncode(character),
    )
    .join("");

  if (/[ .]$/.test(encoded)) {
    encoded = encoded.slice(0, -1) + percentEncode(encoded.slice(-1));
  }

  if (/^\./.test(encoded)) encoded = percentEncode(".") + encoded.slice(1);

  if (RESERVED_WINDOWS_NAMES.test(encoded.split(".")[0])) {
    encoded = percentEncode(encoded[0]) + encoded.slice(1);
  }

  return encoded;
}

class FileWriter {
  readonly files: FileMap = new Map();
  private readonly lowerCasePaths = new Map<string, string>();

  put(path: string, content: string, what: string): void {
    // Case-insensitive file systems (Windows, macOS) would merge two names that differ only in case.
    const clash = this.lowerCasePaths.get(path.toLowerCase());

    if (clash !== undefined) {
      throw new LayoutError(
        `${what} would be written to ${path}, which clashes with ${clash} (names must differ by more than letter case).`,
      );
    }

    this.lowerCasePaths.set(path.toLowerCase(), path);
    this.files.set(path, content);
  }

  json(path: string, value: Json, what: string): void {
    this.put(path, stableStringify(value), what);
  }
}

function bodyExtension(pluginId: string | undefined, body: string): string {
  if (pluginId !== undefined && SQL_PLUGINS.has(pluginId)) return ".sql";

  const trimmed = body.trim();

  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      JSON.parse(trimmed);

      return ".json";
    } catch {
      // Not JSON (often JSON with {{ bindings }}): plain text.
    }
  }

  return ".txt";
}

function splitWidgets(dsl: JsonObject, dir: string, out: FileWriter): void {
  const seen = new Set<string>();

  const visit = (widget: JsonObject): string => {
    const name = text(widget.widgetName);

    if (name === undefined) {
      throw new LayoutError(`A widget in ${dir} has no widgetName.`);
    }

    if (seen.has(name)) {
      throw new LayoutError(`Two widgets in ${dir} are named ${name}.`);
    }

    seen.add(name);

    const props: JsonObject = { ...widget };

    if (Array.isArray(widget.children)) {
      props.children = widget.children.map((child) => {
        if (!isObject(child)) {
          throw new LayoutError(`${name} in ${dir} has a child that is not a widget.`);
        }

        return visit(child);
      });
    }

    out.json(`${dir}/${fileName(name)}.json`, props, `Widget ${name}`);

    return name;
  };

  visit(dsl);
}

/** Splits an app JSON (an export of the edit copy) into the projects repo's files. */
export function splitApp(appJson: JsonObject): FileMap {
  const app = clone(appJson);
  const out = new FileWriter();

  const application: JsonObject = {};

  for (const [key, value] of Object.entries(app)) {
    if (!SPLIT_KEYS.includes(key) && !DROPPED_KEYS.includes(key)) {
      application[key] = value;
    }
  }

  const exported = object(application.exportedApplication);

  if (exported === undefined) {
    throw new LayoutError("The app JSON has no exportedApplication.");
  }

  for (const key of PUBLISHED_APPLICATION_KEYS) delete exported[key];

  // An app without tags has no "tags" in application.json, whether the instance has none or an empty list.
  if (Array.isArray(exported.tags) && exported.tags.length === 0) delete exported.tags;

  out.json(APPLICATION_FILE, withoutVolatile(application), "The application");

  const theme = object(app.editModeTheme);

  if (theme !== undefined) out.json(THEME_FILE, withoutVolatile(theme), "The theme");

  const datasources = (Array.isArray(app.datasourceList) ? app.datasourceList : [])
    .filter(isObject)
    .map((datasource) => {
      const reduced: JsonObject = {};

      for (const key of ["name", "pluginId"]) {
        if (datasource[key] !== undefined && datasource[key] !== null) {
          reduced[key] = datasource[key];
        }
      }

      return reduced;
    })
    .sort((a, b) =>
      compareText(
        `${text(a.name)}\u0000${text(a.pluginId)}`,
        `${text(b.name)}\u0000${text(b.pluginId)}`,
      ),
    );

  out.json(DATASOURCES_FILE, datasources, "The datasource list");

  const libraries = (Array.isArray(app.customJSLibList) ? app.customJSLibList : [])
    .filter(isObject)
    .map((library) => withoutVolatile(library) as JsonObject)
    .sort((a, b) =>
      compareText(
        text(a.uidString) ?? text(a.name) ?? "",
        text(b.uidString) ?? text(b.name) ?? "",
      ),
    );

  if (libraries.length > 0) {
    out.json(JS_LIBRARIES_FILE, libraries, "The JS library list");
  }

  // Pages, by the name their queries and JS objects refer to them with.
  const pageFolders = new Map<string, string>();

  for (const page of (Array.isArray(app.pageList) ? app.pageList : []).filter(isObject)) {
    if (isDeleted(page, "unpublishedPage")) continue;

    const editPage = object(page.unpublishedPage);
    const name = text(editPage?.name);

    if (editPage === undefined || name === undefined) {
      throw new LayoutError("A page has no edit-mode name.");
    }

    const folder = `pages/${fileName(name)}`;
    const meta: JsonObject = { ...page };

    delete meta.publishedPage;

    const metaPage: JsonObject = { ...editPage };
    const layouts = Array.isArray(editPage.layouts) ? editPage.layouts : [];
    const firstLayout = object(layouts[0]);
    const dsl = object(firstLayout?.dsl);

    // A page has one layout; any other shape is kept as it is, widgets included.
    if (layouts.length === 1 && firstLayout !== undefined && dsl !== undefined) {
      const layout: JsonObject = { ...firstLayout };

      delete layout.dsl;
      metaPage.layouts = [layout];
      splitWidgets(dsl, `${folder}/widgets`, out);
    }

    meta.unpublishedPage = metaPage;
    out.json(`${folder}/${PAGE_FILE}`, withoutVolatile(meta), `Page ${name}`);
    pageFolders.set(name, folder);
  }

  const folderOf = (pageId: string | undefined, what: string): string => {
    const folder = pageId === undefined ? undefined : pageFolders.get(pageId);

    if (folder === undefined) {
      throw new LayoutError(`${what} belongs to page "${pageId}", which the app does not have.`);
    }

    return folder;
  };

  const collections = (Array.isArray(app.actionCollectionList) ? app.actionCollectionList : [])
    .filter(isObject)
    .filter((collection) => !isDeleted(collection, "unpublishedCollection"));
  const functionsByCollection = new Map<string, JsonObject[]>();
  const queries: JsonObject[] = [];

  for (const action of (Array.isArray(app.actionList) ? app.actionList : []).filter(isObject)) {
    if (isDeleted(action, "unpublishedAction")) continue;

    const collectionId = text(object(action.unpublishedAction)?.collectionId);
    const ownedByCollection =
      collectionId !== undefined &&
      collections.some((collection) => text(collection.id) === collectionId);

    if (ownedByCollection) {
      const list = functionsByCollection.get(collectionId) ?? [];

      list.push(action);
      functionsByCollection.set(collectionId, list);
    } else {
      queries.push(action);
    }
  }

  for (const action of queries) {
    const meta: JsonObject = { ...action };

    delete meta.publishedAction;

    const editAction = object(meta.unpublishedAction);
    const name = text(editAction?.name);

    if (editAction === undefined || name === undefined) {
      throw new LayoutError("A query has no edit-mode name.");
    }

    const folder = folderOf(text(editAction.pageId), `Query ${name}`);
    const base = `${folder}/queries/${fileName(name)}`;
    const configuration = object(editAction.actionConfiguration);
    const body = text(configuration?.body);
    const metaAction: JsonObject = { ...editAction };

    if (configuration !== undefined && body !== undefined) {
      const metaConfiguration: JsonObject = { ...configuration };

      delete metaConfiguration.body;
      metaAction.actionConfiguration = metaConfiguration;
      out.put(`${base}${bodyExtension(text(action.pluginId), body)}`, body, `Query ${name}`);
    }

    meta.unpublishedAction = metaAction;
    out.json(`${base}${QUERY_SUFFIX}`, withoutVolatile(meta), `Query ${name}`);
  }

  for (const collection of collections) {
    const meta: JsonObject = { ...collection };

    delete meta.publishedCollection;

    const editCollection = object(meta.unpublishedCollection);
    const name = text(editCollection?.name);

    if (editCollection === undefined || name === undefined) {
      throw new LayoutError("A JS object has no edit-mode name.");
    }

    const folder = folderOf(text(editCollection.pageId), `JS object ${name}`);
    const base = `${folder}/jsobjects/${fileName(name)}`;
    const metaCollection: JsonObject = { ...editCollection };
    const body = text(editCollection.body);

    delete metaCollection.body;
    meta.unpublishedCollection = metaCollection;

    // A function's code is part of the JS object's code, so only its settings are kept here (as Appsmith's own git
    // format does): the code lives in <JS>.js alone.
    const functions = (functionsByCollection.get(text(collection.id) ?? "") ?? [])
      .map((action) => {
        const fn: JsonObject = { ...action };

        delete fn.publishedAction;

        const editAction = object(fn.unpublishedAction);

        if (editAction !== undefined) {
          const metaAction: JsonObject = { ...editAction };
          const configuration = object(editAction.actionConfiguration);

          delete metaAction.jsonPathKeys;

          if (configuration !== undefined) {
            const metaConfiguration: JsonObject = { ...configuration };

            delete metaConfiguration.body;
            metaAction.actionConfiguration = metaConfiguration;
          }

          fn.unpublishedAction = metaAction;
        }

        return withoutVolatile(fn) as JsonObject;
      })
      .sort((a, b) =>
        compareText(
          text(object(a.unpublishedAction)?.name) ?? "",
          text(object(b.unpublishedAction)?.name) ?? "",
        ),
      );

    out.json(
      `${base}${JS_OBJECT_SUFFIX}`,
      { collection: withoutVolatile(meta), functions },
      `JS object ${name}`,
    );

    if (body !== undefined) out.put(`${base}${JS_BODY_SUFFIX}`, body, `JS object ${name}`);
  }

  return out.files;
}

class FileReader {
  private readonly files: FileMap;
  private readonly unread: Set<string>;

  constructor(files: FileMap) {
    this.files = files;
    // Dot files (.DS_Store and the like) are not part of the app.
    this.unread = new Set(
      [...files.keys()].filter((path) => !path.split("/").some((part) => part.startsWith("."))),
    );
  }

  has(path: string): boolean {
    return this.files.has(path);
  }

  text(path: string): string {
    const content = this.files.get(path);

    if (content === undefined) throw new LayoutError(`${path} is missing.`);

    this.unread.delete(path);

    return content;
  }

  json(path: string): Json {
    return parseJson(this.text(path), path);
  }

  object(path: string): JsonObject {
    const value = this.json(path);

    if (!isObject(value)) throw new LayoutError(`${path} must hold a JSON object.`);

    return value;
  }

  paths(prefix: string, suffix: string): string[] {
    return [...this.files.keys()]
      .filter(
        (path) =>
          path.startsWith(prefix) &&
          path.endsWith(suffix) &&
          !path.slice(prefix.length).includes("/"),
      )
      .sort();
  }

  leftOver(): string[] {
    return [...this.unread].sort();
  }
}

function assembleWidgets(reader: FileReader, dir: string): JsonObject {
  const widgets = new Map<string, JsonObject>();

  for (const path of reader.paths(`${dir}/`, ".json")) {
    const widget = reader.object(path);
    const name = text(widget.widgetName);

    if (name === undefined) throw new LayoutError(`${path} has no widgetName.`);

    if (widgets.has(name)) throw new LayoutError(`Two widget files in ${dir} are named ${name}.`);

    widgets.set(name, widget);
  }

  const childNames = new Set<string>();

  for (const [name, widget] of widgets) {
    if (!Array.isArray(widget.children)) continue;

    for (const child of widget.children) {
      if (typeof child !== "string") {
        throw new LayoutError(`${name} in ${dir}: "children" must list widget names.`);
      }

      if (childNames.has(child)) {
        throw new LayoutError(`${child} in ${dir} is listed as a child of more than one widget.`);
      }

      childNames.add(child);
    }
  }

  const roots = [...widgets.keys()].filter((name) => !childNames.has(name));

  if (roots.length !== 1) {
    throw new LayoutError(
      `${dir} must have exactly one top widget (usually MainContainer); found ${roots.length === 0 ? "none" : roots.join(", ")}. Every other widget must be listed in its parent's "children".`,
    );
  }

  const build = (name: string, trail: string[]): JsonObject => {
    const widget = widgets.get(name);

    if (widget === undefined) {
      throw new LayoutError(`${trail.at(-1)} in ${dir} lists child ${name}, which has no file.`);
    }

    if (trail.includes(name)) throw new LayoutError(`${name} in ${dir} contains itself.`);

    const result: JsonObject = { ...widget };

    if (Array.isArray(widget.children)) {
      result.children = widget.children.map((child) => build(child as string, [...trail, name]));
    }

    return result;
  };

  return build(roots[0], []);
}

function bodyPath(reader: FileReader, base: string, what: string): string | undefined {
  const found = BODY_EXTENSIONS.map((extension) => `${base}${extension}`).filter((path) =>
    reader.has(path),
  );

  if (found.length > 1) {
    throw new LayoutError(`${what} has more than one body file: ${found.join(", ")}. Keep one.`);
  }

  return found[0];
}

/** Puts the projects repo's files of one app back into an app JSON for import_application. */
export function assembleApp(files: FileMap): JsonObject {
  const reader = new FileReader(files);
  const app: JsonObject = { ...reader.object(APPLICATION_FILE) };

  if (!isObject(app.exportedApplication)) {
    throw new LayoutError(`${APPLICATION_FILE} has no exportedApplication.`);
  }

  // The file is the truth for the tags: no "tags" means none, so removing the key clears them on the next push (the
  // import keeps stored values the file leaves out).
  const exported: JsonObject = { ...app.exportedApplication };

  if (!Array.isArray(exported.tags)) exported.tags = [];

  app.exportedApplication = exported;

  if (reader.has(THEME_FILE)) app.editModeTheme = reader.object(THEME_FILE);

  const datasources = reader.has(DATASOURCES_FILE) ? reader.json(DATASOURCES_FILE) : [];

  if (!Array.isArray(datasources)) throw new LayoutError(`${DATASOURCES_FILE} must hold a list.`);

  app.datasourceList = datasources;

  const libraries = reader.has(JS_LIBRARIES_FILE) ? reader.json(JS_LIBRARIES_FILE) : [];

  if (!Array.isArray(libraries)) throw new LayoutError(`${JS_LIBRARIES_FILE} must hold a list.`);

  app.customJSLibList = libraries;

  const pageList: Json[] = [];
  const actionList: Json[] = [];
  const actionCollectionList: Json[] = [];
  const pageFolders = [...files.keys()]
    .map((path) => /^pages\/([^/]+)\/page\.json$/.exec(path))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => `pages/${match[1]}`)
    .sort();

  for (const folder of pageFolders) {
    const page = reader.object(`${folder}/${PAGE_FILE}`);
    const editPage = object(page.unpublishedPage);
    const pageName = text(editPage?.name);

    if (editPage === undefined || pageName === undefined) {
      throw new LayoutError(`${folder}/${PAGE_FILE} has no unpublishedPage.name.`);
    }

    const widgetsDir = `${folder}/widgets`;

    if (reader.paths(`${widgetsDir}/`, ".json").length > 0) {
      const layouts = Array.isArray(editPage.layouts) ? editPage.layouts : [];
      const layout = object(layouts[0]);

      if (layouts.length !== 1 || layout === undefined) {
        throw new LayoutError(`${folder}/${PAGE_FILE} must have exactly one layout to hold its widgets.`);
      }

      layout.dsl = assembleWidgets(reader, widgetsDir);
    }

    pageList.push(page);

    for (const path of reader.paths(`${folder}/queries/`, QUERY_SUFFIX)) {
      const action = reader.object(path);
      const editAction = object(action.unpublishedAction);

      if (editAction === undefined) throw new LayoutError(`${path} has no unpublishedAction.`);

      editAction.pageId = pageName;

      const base = path.slice(0, -QUERY_SUFFIX.length);
      const body = bodyPath(reader, base, path);

      if (body !== undefined) {
        const configuration = object(editAction.actionConfiguration) ?? {};

        configuration.body = reader.text(body);
        editAction.actionConfiguration = configuration;
      }

      actionList.push(action);
    }

    for (const path of reader.paths(`${folder}/jsobjects/`, JS_OBJECT_SUFFIX)) {
      const entry = reader.object(path);
      const collection = object(entry.collection);
      const editCollection = object(collection?.unpublishedCollection);

      if (collection === undefined || editCollection === undefined) {
        throw new LayoutError(`${path} has no collection.unpublishedCollection.`);
      }

      editCollection.pageId = pageName;

      const bodyFile = `${path.slice(0, -JS_OBJECT_SUFFIX.length)}${JS_BODY_SUFFIX}`;

      if (reader.has(bodyFile)) editCollection.body = reader.text(bodyFile);

      actionCollectionList.push(collection);

      const functions = Array.isArray(entry.functions) ? entry.functions : [];

      for (const fn of functions) {
        const editAction = object(object(fn)?.unpublishedAction);

        if (editAction !== undefined) editAction.pageId = pageName;

        actionList.push(fn);
      }
    }
  }

  const leftOver = reader.leftOver();

  if (leftOver.length > 0) {
    throw new LayoutError(
      `These files are not part of the app layout (see the projects repo's CLAUDE.md): ${leftOver.join(", ")}`,
    );
  }

  app.pageList = pageList;
  app.actionList = actionList;
  app.actionCollectionList = actionCollectionList;

  return app;
}

export type Change = "modified" | "only-in-repo" | "only-in-instance";

/** The files that differ between the repo and the instance, sorted by path. */
export function diffFiles(repo: FileMap, instance: FileMap): { path: string; change: Change }[] {
  const paths = new Set([...repo.keys(), ...instance.keys()]);

  return [...paths]
    .sort(compareText)
    .flatMap((path): { path: string; change: Change }[] => {
      const local = repo.get(path);
      const remote = instance.get(path);

      if (local === remote) return [];

      if (remote === undefined) return [{ path, change: "only-in-repo" }];

      if (local === undefined) return [{ path, change: "only-in-instance" }];

      return [{ path, change: "modified" }];
    });
}

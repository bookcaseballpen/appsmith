import { mkdir, readdir, readFile, rm, rmdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import type { FileMap } from "./layout.ts";

// Reads and writes one app folder (apps/<app-slug>/) as a map of relative, "/"-separated paths to file contents. Files
// and folders whose name starts with "." are never read, written or deleted.

function hidden(name: string): boolean {
  return name.startsWith(".");
}

export async function readTree(dir: string): Promise<FileMap> {
  const files: FileMap = new Map();

  const walk = async (relative: string): Promise<void> => {
    const entries = await readdir(join(dir, relative), { withFileTypes: true });

    for (const entry of entries) {
      if (hidden(entry.name)) continue;

      const path = relative === "" ? entry.name : `${relative}/${entry.name}`;

      if (entry.isDirectory()) await walk(path);
      else if (entry.isFile()) files.set(path, await readFile(join(dir, path), "utf8"));
    }
  };

  await walk("");

  return files;
}

export interface WriteSummary {
  written: string[];
  deleted: string[];
  unchanged: number;
}

/** Makes {@code dir} hold exactly {@code files}: writes what changed, deletes what is gone, removes emptied folders. */
export async function writeTree(dir: string, files: FileMap): Promise<WriteSummary> {
  await mkdir(dir, { recursive: true });

  const existing = await readTree(dir);
  const summary: WriteSummary = { written: [], deleted: [], unchanged: 0 };

  for (const path of [...existing.keys()].sort()) {
    if (!files.has(path)) {
      await rm(join(dir, path));
      summary.deleted.push(path);
    }
  }

  for (const [path, content] of [...files].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) {
    if (existing.get(path) === content) {
      summary.unchanged += 1;
      continue;
    }

    const parent = path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";

    await mkdir(join(dir, parent), { recursive: true });
    await writeFile(join(dir, path), content, "utf8");
    summary.written.push(path);
  }

  await removeEmptyFolders(dir, "");

  return summary;
}

async function removeEmptyFolders(dir: string, relative: string): Promise<boolean> {
  const entries = await readdir(join(dir, relative), { withFileTypes: true });
  let empty = true;

  for (const entry of entries) {
    if (entry.isDirectory() && !hidden(entry.name)) {
      const child = relative === "" ? entry.name : `${relative}/${entry.name}`;

      if (await removeEmptyFolders(dir, child)) {
        await rmdir(join(dir, child));
        continue;
      }
    }

    empty = false;
  }

  return empty;
}

// Deterministic JSON for the projects repo: object keys sorted at every level, two-space indent, LF line ends and a
// final newline, so the same app always gives the same bytes and a diff shows only real changes.

export type Json =
  | null
  | boolean
  | number
  | string
  | Json[]
  | { [key: string]: Json };

export type JsonObject = { [key: string]: Json };

export function isObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function sortKeys(value: Json): Json {
  if (Array.isArray(value)) return value.map(sortKeys);

  if (isObject(value)) {
    const sorted: JsonObject = {};

    for (const key of Object.keys(value).sort()) {
      const child = value[key];

      // undefined is not JSON; it can only come from code that deleted a key by assignment.
      if (child !== undefined) sorted[key] = sortKeys(child);
    }

    return sorted;
  }

  return value;
}

export function stableStringify(value: Json): string {
  return `${JSON.stringify(sortKeys(value), null, 2)}\n`;
}

export function parseJson(text: string, where: string): Json {
  try {
    return JSON.parse(text) as Json;
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);

    throw new Error(`${where} is not valid JSON: ${reason}`);
  }
}

export function clone<T extends Json>(value: T): T {
  return structuredClone(value);
}

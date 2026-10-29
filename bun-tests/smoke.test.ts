// Placeholder so `bun test` at the repo root finds at least one test. See bunfig.toml.
import { expect, test } from "bun:test";

test("bun test runs", () => {
  expect(1 + 1).toBe(2);
});

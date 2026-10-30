#!/usr/bin/env node
import { run } from "./commands.ts";

// The cab-sync command line (see commands.ts and README.md).

process.exitCode = await run(process.argv.slice(2), process.env, {
  out: (line) => process.stdout.write(`${line}\n`),
  err: (line) => process.stderr.write(`${line}\n`),
});

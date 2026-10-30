import type { ToolAnnotations } from "@modelcontextprotocol/sdk/types.js";
import type { ToolCatalogEntry } from "../../builder/capabilities.js";
import type { InstructionDoc } from "../../builder/instructions.js";

// Fork: the data for the release-approval and projects-repo tools (registered in ee/extensions/tools.ts). The contract
// is in ce/extensions/catalog.ts; this file is data only, with `import type` and nothing else, because core modules
// read it at module load. Approving and rejecting are done by a human Approver in the Appsmith UI, so neither is a
// tool.

export const EXTENSION_TOOL_CATALOG: readonly ToolCatalogEntry[] = [
  {
    name: "submit_for_approval",
    gate: "always",
    summary:
      "submit the app's current edit-mode version for release approval (only after UAT, when the developer asks)",
  },
  {
    name: "get_approval_status",
    gate: "always",
    summary: "read an app's release-approval state and history",
  },
  {
    name: "export_application",
    gate: "always",
    summary:
      "export an app's edit copy as app JSON for the projects repo (no published copy, datasources by name and plugin only)",
  },
  {
    name: "import_application",
    gate: "always",
    summary:
      "import app JSON as a new app or into an existing app's edit copy (what users see changes only through approval)",
  },
  {
    name: "set_app_tags",
    gate: "always",
    summary:
      "replace an app's tags, the free-text labels the home page catalog filters by (metadata: no approval change)",
  },
];

export const EXTENSION_TOOL_ANNOTATIONS: Readonly<
  Record<string, ToolAnnotations>
> = {
  // Records a request on the instance; it publishes nothing (a human approves), and repeating it is refused.
  submit_for_approval: {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: false,
    openWorldHint: false,
  },
  get_approval_status: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  export_application: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  // Writes the unpublished edit copy only, like the core authoring tools (WRITE in toolAnnotations.ts): the published
  // copy users see is kept, and changes only when an Approver approves a release. Not idempotent: without an
  // applicationId it creates a new app each time.
  import_application: {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: false,
    openWorldHint: false,
  },
  // WRITE_IDEMPOTENT in toolAnnotations.ts: it replaces the whole tag list, so repeating it changes nothing more.
  set_app_tags: {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
};

export const EXTENSION_GATE_REQUIREMENTS: Readonly<
  Record<string, { requires: string; provides: string }>
> = {};

export const EXTENSION_GUIDES: readonly InstructionDoc[] = [];

// Typed as string: core compares it with "" (a literal type here would make that comparison a type error).
export const EXTENSION_SERVER_INSTRUCTIONS: string =
  "Release approval: when the developer has finished testing the app and asks to submit it, call submit_for_approval with the application id you work on. It freezes the current edit-mode version; any later change cancels the request, so submit again after changing the app. Check progress with get_approval_status. Approving and rejecting are done by a human Approver in the Appsmith UI and are not available here. " +
  "Projects repo: apps are kept as files in a separate repository (tools/cab-sync splits them). export_application returns an app's edit copy as app JSON and import_application writes app JSON into an app's edit copy (or creates a new app); the published version users see never changes through them. " +
  "Tags: list_applications shows each app's tags and filters by them; set_app_tags replaces an app's tags (free text, lower-cased, at most 10); tags never affect a release approval.";

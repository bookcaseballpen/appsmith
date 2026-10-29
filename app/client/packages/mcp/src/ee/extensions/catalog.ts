import type { ToolAnnotations } from "@modelcontextprotocol/sdk/types.js";
import type { ToolCatalogEntry } from "../../builder/capabilities.js";
import type { InstructionDoc } from "../../builder/instructions.js";

// Fork: the data for the release-approval tools (registered in ee/extensions/tools.ts). The contract is in
// ce/extensions/catalog.ts; this file is data only, with `import type` and nothing else, because core modules read it
// at module load. Approving and rejecting are done by a human Approver in the Appsmith UI, so neither is a tool.

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
};

export const EXTENSION_GATE_REQUIREMENTS: Readonly<
  Record<string, { requires: string; provides: string }>
> = {};

export const EXTENSION_GUIDES: readonly InstructionDoc[] = [];

// Typed as string: core compares it with "" (a literal type here would make that comparison a type error).
export const EXTENSION_SERVER_INSTRUCTIONS: string =
  "Release approval: when the developer has finished testing the app and asks to submit it, call submit_for_approval with the application id you work on. It freezes the current edit-mode version; any later change cancels the request, so submit again after changing the app. Check progress with get_approval_status. Approving and rejecting are done by a human Approver in the Appsmith UI and are not available here.";

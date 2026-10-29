import type { DefaultRootState } from "react-redux";

/**
 * Fork: the read-only editor. Apps are built in the projects repo and reach the server through app import or MCP;
 * the editor only runs and debugs them, for every role. The server refuses editor changes and sends the switch as the
 * `fork_editor_read_only` feature flag (APPSMITH_EDITOR_READ_ONLY, on by default). The client only hides the editing
 * UI.
 */
export const selectIsEditorReadOnly = (state: DefaultRootState): boolean => {
  const featureFlag = state.ui?.users?.featureFlag;

  return Boolean(
    featureFlag?.overriddenFlags?.fork_editor_read_only ??
      featureFlag?.data?.fork_editor_read_only,
  );
};

// The permission helpers (ee/utils/BusinessFeatures/permissionPageHelpers) are plain functions called from many
// components and sagas, so they read this copy of the flag. editorReadOnlySagas keeps it in step with the store.
let editorReadOnly = false;

export const setEditorReadOnly = (readOnly: boolean) => {
  editorReadOnly = readOnly;
};

export const isEditorReadOnly = () => editorReadOnly;

export const EDITOR_READ_ONLY_BANNER =
  "Read-only: edit this app through the projects repo (MCP).";

export const EDITOR_READ_ONLY_WIDGETS_NOTE =
  "Widgets can't be added or changed here. The canvas runs the app: use it to test queries and JS.";

import { createSelector } from "reselect";
import {
  getCurrentGitBranch,
  getIsGitSyncModalOpen,
  protectedModeSelector,
} from "./gitSyncSelectors";
import {
  selectGitCurrentBranch as selectGitCurrentBranchNew,
  selectGitProtectedMode as selectGitProtectedModeNew,
  selectGitOpsModalOpen as selectGitOpsModalOpenNew,
  selectGitConnectModalOpen as selectGitConnectModalOpenNew,
} from "git/store/selectors";
import type { DefaultRootState } from "react-redux";
import { applicationArtifact } from "git-artifact-helpers/application";
import { selectIsReviewOnlyUser } from "ee/utils/instanceRoles";
import { selectIsEditorReadOnly } from "ee/utils/editorReadOnly";

export const selectGitModEnabled = createSelector(
  (state: DefaultRootState) => {
    return {
      ...state.ui.users.featureFlag.data,
      ...state.ui.users.featureFlag.overriddenFlags,
    };
  },
  (featureFlags) => featureFlags.release_git_modularisation_enabled ?? false,
);

export const selectGitApplicationArtifactDef = createSelector(
  (state: DefaultRootState) => state.entities.pageList.baseApplicationId || "",
  (baseApplicationId) => applicationArtifact(baseApplicationId),
);

export const selectGitApplicationCurrentBranch = createSelector(
  selectGitModEnabled,
  getCurrentGitBranch,
  (state) =>
    selectGitCurrentBranchNew(state, selectGitApplicationArtifactDef(state)),
  (isGitModEnabled, currentBranchOld, currentBranchNew) => {
    return isGitModEnabled ? currentBranchNew : currentBranchOld;
  },
);

export const selectGitApplicationProtectedMode = createSelector(
  selectGitModEnabled,
  protectedModeSelector,
  (state) =>
    selectGitProtectedModeNew(state, selectGitApplicationArtifactDef(state)),
  (isGitModEnabled, protectedModeOld, protectedModeNew) => {
    return isGitModEnabled ? protectedModeNew : protectedModeOld;
  },
);

/**
 * Fork: the modes in which the editor shows the canvas only, with no explorer, property pane or debugger. The IDE
 * layout reads this one; everything else reads selectCombinedPreviewMode.
 */
export const selectCanvasOnlyMode = createSelector(
  // need to do this to avoid circular dependency
  (state: DefaultRootState) => state.ui.editor.isPreviewMode,
  selectGitApplicationProtectedMode,
  // Approvers review apps read-only, so the editor always stays in preview for them.
  selectIsReviewOnlyUser,
  (isPreviewMode, isProtectedMode, isReviewOnlyUser) =>
    isPreviewMode || isProtectedMode || isReviewOnlyUser,
);

export const selectCombinedPreviewMode = createSelector(
  selectCanvasOnlyMode,
  // Fork: in the read-only editor the canvas always runs as in preview (no drag, drop, resize, selection, widget
  // names or property pane, and no layout saves), but the IDE keeps its query and JS panes.
  selectIsEditorReadOnly,
  (isCanvasOnlyMode, isEditorReadOnly) => isCanvasOnlyMode || isEditorReadOnly,
);

export const selectGitOpsModalOpen = createSelector(
  selectGitModEnabled,
  getIsGitSyncModalOpen,
  (state) =>
    selectGitOpsModalOpenNew(state, selectGitApplicationArtifactDef(state)),
  (isGitModEnabled, isOldModalOpen, isNewModalOpen) => {
    return isGitModEnabled ? isNewModalOpen : isOldModalOpen;
  },
);

export const selectGitConnectModalOpen = createSelector(
  selectGitModEnabled,
  getIsGitSyncModalOpen,
  (state) =>
    selectGitConnectModalOpenNew(state, selectGitApplicationArtifactDef(state)),
  (isGitModEnabled, isOldModalOpen, isNewModalOpen) => {
    return isGitModEnabled ? isNewModalOpen : isOldModalOpen;
  },
);

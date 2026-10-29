import React from "react";
import { useSelector } from "react-redux";
import { getIsBuiltInGitEnabled } from "ee/selectors/builtInGitSelectors";

import { GitModals as NewGitModals } from "git";

import { useGitModEnabled } from "pages/Editor/gitSync/hooks/modHooks";
import GitSyncModal from "pages/Editor/gitSync/GitSyncModal";
import GitSettingsModal from "pages/Editor/gitSync/GitSettingsModal";
import DisableCDModal from "ee/components/gitComponents/DisableCDModal";
import ReconfigureCDKeyModal from "ee/components/gitComponents/ReconfigureCDKeyModal";
import DisconnectGitModal from "pages/Editor/gitSync/DisconnectGitModal";
import DisableAutocommitModal from "pages/Editor/gitSync/DisableAutocommitModal";
import RepoLimitExceededErrorModal from "pages/Editor/gitSync/RepoLimitExceededErrorModal";

export function GitModals() {
  const isGitModEnabled = useGitModEnabled();
  // Fork: no Git modals while built-in Git is off (APPSMITH_GIT_ENABLED).
  const isBuiltInGitEnabled = useSelector(getIsBuiltInGitEnabled);

  if (!isBuiltInGitEnabled) return null;

  return isGitModEnabled ? (
    <NewGitModals />
  ) : (
    <>
      <GitSyncModal />
      <GitSettingsModal />
      <DisableCDModal />
      <ReconfigureCDKeyModal />
      <DisconnectGitModal />
      <DisableAutocommitModal />
      <RepoLimitExceededErrorModal />
    </>
  );
}

import React from "react";
import {
  RESET_BUTTON,
  SAVE_AND_REFRESH_BUTTON,
  SAVE_AND_RESTART_BUTTON,
  SAVE_BUTTON,
  createMessage,
} from "ee/constants/messages";
import { Button, Text } from "@appsmith/ads";
import styled from "styled-components";
import {
  FORK_SETTINGS_COLORS,
  FORK_SETTINGS_NARROW_WIDTH,
  SettingsFooter,
} from "ee/pages/AdminSettings/SettingsShell";

// Fork: the settings shell's sticky footer (ee/pages/AdminSettings/SettingsShell): what saving does on the left,
// Reset and Save on the right, both disabled until something changes.
const Hint = styled(Text)`
  && {
    margin-right: auto;
    color: ${FORK_SETTINGS_COLORS.muted};
    font-size: 12.5px;
  }

  @media (max-width: ${FORK_SETTINGS_NARROW_WIDTH}px) {
    flex-basis: 100%;
  }
`;

export const UNSAVED_CHANGES_HINT = "You have unsaved changes.";
export const RESTART_HINT = "Saving restarts the server (about 30 seconds).";
export const REFRESH_HINT = "Saving reloads the page.";

interface SaveAdminSettingsProps {
  isOnlyOrganizationConfig?: boolean;
  isSaving?: boolean;
  needsRefresh?: boolean;
  needsRestart?: boolean;
  onSave?: () => void;
  onClear?: () => void;
  settings: Record<string, string>;
  valid: boolean;
  updatedOrganizationSettings?: string[];
}

const saveAdminSettings = (props: SaveAdminSettingsProps) => {
  const {
    isOnlyOrganizationConfig = false,
    isSaving,
    needsRefresh = false,
    needsRestart = false,
    onClear,
    onSave,
    settings,
    updatedOrganizationSettings,
    valid,
  } = props;

  let saveButtonText = SAVE_AND_RESTART_BUTTON;

  if (needsRefresh) {
    saveButtonText = SAVE_AND_REFRESH_BUTTON;
  } else if (
    !needsRestart &&
    (isOnlyOrganizationConfig ||
      (updatedOrganizationSettings?.length === Object.keys(settings).length &&
        updatedOrganizationSettings?.length !== 0))
  ) {
    saveButtonText = SAVE_BUTTON;
  }

  const isDirty = Object.keys(settings).length !== 0;
  const effectHint =
    saveButtonText === SAVE_AND_RESTART_BUTTON
      ? RESTART_HINT
      : saveButtonText === SAVE_AND_REFRESH_BUTTON
        ? REFRESH_HINT
        : "";
  const hint = [isDirty ? UNSAVED_CHANGES_HINT : "", effectHint]
    .filter(Boolean)
    .join(" ");

  return (
    <SettingsFooter data-testid="t--admin-settings-footer">
      <Hint data-testid="t--admin-settings-footer-hint" renderAs="p">
        {hint}
      </Hint>
      <Button
        className="t--admin-settings-reset-button"
        isDisabled={!isDirty}
        kind="secondary"
        onClick={onClear}
        size="md"
      >
        {createMessage(RESET_BUTTON)}
      </Button>
      <Button
        className="t--admin-settings-save-button"
        isDisabled={!isDirty || !valid}
        isLoading={isSaving}
        onClick={onSave}
        size="md"
      >
        {createMessage(saveButtonText)}
      </Button>
    </SettingsFooter>
  );
};

export default saveAdminSettings;

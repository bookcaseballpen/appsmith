import type { AdminConfigType } from "ee/pages/AdminSettings/config/types";
import {
  APPSMITH_INSTANCE_NAME_SETTING_SETTING,
  config as CE_CONFIG,
} from "ce/pages/AdminSettings/config/instanceSettings";

export * from "ce/pages/AdminSettings/config/instanceSettings";

/** Fork: the instance name moved to the General page (ee/pages/AdminSettings/config/general.tsx). */
export const config: AdminConfigType = {
  ...CE_CONFIG,
  settings: CE_CONFIG.settings?.filter(
    (setting) => setting !== APPSMITH_INSTANCE_NAME_SETTING_SETTING,
  ),
};

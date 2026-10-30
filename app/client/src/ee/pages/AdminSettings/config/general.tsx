import type { AdminConfigType } from "ee/pages/AdminSettings/config/types";
import { SettingCategories } from "ee/pages/AdminSettings/config/types";
import { config as CE_CONFIG } from "ce/pages/AdminSettings/config/general";
import { APPSMITH_INSTANCE_NAME_SETTING_SETTING } from "ce/pages/AdminSettings/config/instanceSettings";
import { PRODUCT_NAME_SUFFIX } from "constants/Brand";

export * from "ce/pages/AdminSettings/config/general";

/**
 * Fork: the General page holds only the instance name (moved here from "Instance settings"). "Anonymous usage data"
 * is gone, since the fork sends no telemetry, and the watermark switch with it (the viewer shows no badge). See
 * FORK.md, "Admin settings".
 */
export const config: AdminConfigType = {
  ...CE_CONFIG,
  subText: `Name of this ${PRODUCT_NAME_SUFFIX} instance.`,
  settings: [
    {
      ...APPSMITH_INSTANCE_NAME_SETTING_SETTING,
      category: SettingCategories.GENERAL,
      subText: "Shown in the emails this instance sends.",
    },
  ],
};

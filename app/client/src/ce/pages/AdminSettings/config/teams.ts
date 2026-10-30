import type { AdminConfigType } from "ee/pages/AdminSettings/config/types";
import {
  CategoryType,
  SettingTypes,
} from "ee/pages/AdminSettings/config/types";
import TeamsSettings from "pages/AdminSettings/Teams";

// Fork: teams for app access (see "Teams and app grants" in FORK.md).
export const config: AdminConfigType = {
  type: "teams",
  categoryType: CategoryType.USER_MANAGEMENT,
  controlType: SettingTypes.PAGE,
  canSave: false,
  title: "Teams",
  icon: "group-2-line",
  component: TeamsSettings,
  isFeatureEnabled: true,
} as AdminConfigType;

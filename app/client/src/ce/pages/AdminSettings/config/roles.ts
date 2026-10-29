import type { AdminConfigType } from "ee/pages/AdminSettings/config/types";
import {
  CategoryType,
  SettingTypes,
} from "ee/pages/AdminSettings/config/types";
import RolesSettings from "pages/AdminSettings/Roles";

export const config: AdminConfigType = {
  type: "user-roles",
  categoryType: CategoryType.USER_MANAGEMENT,
  controlType: SettingTypes.PAGE,
  canSave: false,
  title: "User roles",
  icon: "user-3-line",
  component: RolesSettings,
  isFeatureEnabled: true,
} as AdminConfigType;

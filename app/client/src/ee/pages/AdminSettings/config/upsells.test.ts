import type {
  AdminConfigType,
  Setting,
} from "ee/pages/AdminSettings/config/types";
import {
  CategoryType,
  SettingTypes,
} from "ee/pages/AdminSettings/config/types";
import {
  isDroppedUpsellCategory,
  withoutUpsells,
} from "ee/pages/AdminSettings/config/upsells";
import { config as AuditLogsConfig } from "ee/pages/AdminSettings/config/auditlogs";
import { config as AccessControlConfig } from "ee/pages/AdminSettings/config/userlisting";
import { config as ProvisioningConfig } from "ee/pages/AdminSettings/config/provisioning";
import { config as BrandingConfig } from "ee/pages/AdminSettings/config/branding";
import { config as UserSettingsConfig } from "ee/pages/AdminSettings/config/userSettings";
import { config as ConfigurationConfig } from "ee/pages/AdminSettings/config/configuration";
import { config as GeneralConfig } from "ee/pages/AdminSettings/config/general";
import { config as InstanceSettingsConfig } from "ee/pages/AdminSettings/config/instanceSettings";

const page = (
  type: string,
  settings?: Setting[],
  extra: Partial<AdminConfigType> = {},
): AdminConfigType => ({
  type,
  title: type,
  controlType: SettingTypes.GROUP,
  categoryType: CategoryType.ORGANIZATION,
  canSave: true,
  settings,
  ...extra,
});

const field = (id: string, isFeatureEnabled?: boolean): Setting => ({
  id,
  controlType: SettingTypes.TEXTINPUT,
  isFeatureEnabled,
});

// Fork: no Business or Enterprise upsell in Admin settings (ce/utils/forkHiddenFeatures.ts, areUpsellsHidden).
describe("withoutUpsells", () => {
  it("returns a page without upsells as it is", () => {
    const config = page("plain", [field("a"), field("b", true)]);

    expect(withoutUpsells(config)).toBe(config);
  });

  it("drops a locked page and remembers it", () => {
    expect(
      withoutUpsells(page("locked", [field("a")], { isFeatureEnabled: false })),
    ).toBeUndefined();
    expect(isDroppedUpsellCategory("locked")).toBe(true);
    expect(isDroppedUpsellCategory("plain")).toBe(false);
  });

  it("drops the locked fields of a page and keeps the rest", () => {
    const kept = field("kept");
    const result = withoutUpsells(
      page("mixed", [kept, field("locked", false)]),
    );

    expect(result?.settings).toEqual([kept]);
  });

  it("drops a page whose every field is locked", () => {
    expect(
      withoutUpsells(
        page("all-locked", [field("x", false), field("y", false)]),
      ),
    ).toBeUndefined();
    expect(isDroppedUpsellCategory("all-locked")).toBe(true);
  });

  it("keeps a page with its own component even when it has no fields left", () => {
    const config = page("custom", [field("x", false)], {
      component: () => null,
    });

    expect(withoutUpsells(config)?.settings).toEqual([]);
  });

  it("drops locked sub-pages", () => {
    const open = page("open");
    const result = withoutUpsells(
      page("parent", undefined, {
        children: [open, page("closed", [], { isFeatureEnabled: false })],
      }),
    );

    expect(result?.children).toEqual([open]);
  });

  it.each([
    ["Audit logs", AuditLogsConfig],
    ["Access control", AccessControlConfig],
    ["Provisioning", ProvisioningConfig],
    ["Branding", BrandingConfig],
    ["User settings", UserSettingsConfig],
  ])("leaves out the %s page", (_name, config) => {
    expect(withoutUpsells(config)).toBeUndefined();
  });

  it("leaves out the Enterprise connection pool size from Configuration", () => {
    const ids = withoutUpsells(ConfigurationConfig)?.settings?.map(
      (setting) => setting.id,
    );

    expect(ids).not.toContain("connectionMaxPoolSize");
    expect(ids).toContain("APPSMITH_BASE_URL");
  });
});

describe("the General page", () => {
  it("holds only the instance name", () => {
    expect(GeneralConfig.settings?.map((setting) => setting.id)).toEqual([
      "instanceName",
    ]);
    expect(GeneralConfig.settings?.[0].category).toBe("general");
  });

  it("no longer offers anonymous usage data or the watermark", () => {
    const ids = GeneralConfig.settings?.map((setting) => setting.id);

    expect(ids).not.toContain("APPSMITH_DISABLE_TELEMETRY");
    expect(ids).not.toContain("hideWatermark");
  });

  it("is the only page with the instance name", () => {
    expect(
      InstanceSettingsConfig.settings?.map((setting) => setting.id),
    ).not.toContain("instanceName");
  });
});

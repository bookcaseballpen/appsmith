import type {
  AdminConfigType,
  Setting,
} from "ee/pages/AdminSettings/config/types";
import { areUpsellsHidden } from "ee/utils/forkHiddenFeatures";

/**
 * Fork: a paid-plan upsell. Upstream marks a Business or Enterprise page or field with `isFeatureEnabled: false` and
 * shows it locked, with a tier badge (and an upgrade page for a page).
 */
export const isUpsell = (item?: { isFeatureEnabled?: boolean }) =>
  item?.isFeatureEnabled === false;

const droppedCategories = new Set<string>();

/** Whether this admin settings page was left out as an upsell; its URL then leads to the default page. */
export const isDroppedUpsellCategory = (slug?: string) =>
  !!slug && droppedCategories.has(slug);

const sameItems = <T>(a: T[], b: T[]) =>
  a.length === b.length && a.every((item, index) => item === b[index]);

// Unchanged settings keep their identity: upstream code mutates some of them (and the categories) after registering.
const withoutUpsellSettings = (settings: Setting[]): Setting[] => {
  const kept = settings
    .filter((setting) => !isUpsell(setting))
    .map((setting) => {
      const advanced =
        setting.advanced && withoutUpsellSettings(setting.advanced);

      return advanced && advanced !== setting.advanced
        ? { ...setting, advanced }
        : setting;
    });

  return sameItems(kept, settings) ? settings : kept;
};

/**
 * The admin settings category without its upsells (ce/utils/forkHiddenFeatures.ts, `areUpsellsHidden`), or undefined
 * when nothing is left of it: an upsell page, or a page whose every field is an upsell. A category without upsells
 * is returned as it is.
 */
export const withoutUpsells = (
  config: AdminConfigType,
): AdminConfigType | undefined => {
  if (!areUpsellsHidden()) return config;

  if (isUpsell(config)) {
    droppedCategories.add(config.type);

    return undefined;
  }

  const children = config.children
    ?.map(withoutUpsells)
    .filter((child): child is AdminConfigType => !!child);
  const settings = config.settings && withoutUpsellSettings(config.settings);

  if (
    config.settings?.length &&
    settings?.length === 0 &&
    !config.component &&
    !children?.length
  ) {
    droppedCategories.add(config.type);

    return undefined;
  }

  const childrenChanged =
    !!children && !!config.children && !sameItems(children, config.children);

  if (settings === config.settings && !childrenChanged) return config;

  return {
    ...config,
    ...(settings && { settings }),
    ...(childrenChanged && { children }),
  };
};

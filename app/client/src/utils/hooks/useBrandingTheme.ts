import { useSelector } from "react-redux";

import { getOrganizationConfig } from "ee/selectors/organizationSelectors";
import { useLayoutEffect } from "react";
import { getAssetUrl } from "ee/utils/airgapHelpers";
import {
  APPSMITH_BRAND_FAVICON_URL,
  APPSMITH_BRAND_PRIMARY_COLOR,
} from "utils/BrandingUtils";
import { LightModeTheme } from "@appsmith/wds-theming";

const DEFAULT_HREF_ATTRIBUTE = "data-default-href";

/**
 * Fork: index.html declares the brand favicon three times (plain, and light or dark by prefers-color-scheme), and a
 * browser may keep any of them, so a link added after them does not reliably win. A custom favicon therefore replaces
 * the href of every icon link; the brand favicon (or none set) puts the index.html hrefs back.
 */
export const setFavicon = (faviconUrl?: string) => {
  const isCustom = !!faviconUrl && faviconUrl !== APPSMITH_BRAND_FAVICON_URL;
  const links = Array.from(
    document.querySelectorAll<HTMLLinkElement>("link[rel~='icon']"),
  );

  if (isCustom && links.length === 0) {
    const favicon = document.createElement("link");

    favicon.rel = "icon";
    favicon.className = "t--branding-favicon";
    document.head.appendChild(favicon);
    links.push(favicon);
  }

  links.forEach((link) => {
    if (!link.hasAttribute(DEFAULT_HREF_ATTRIBUTE)) {
      link.setAttribute(
        DEFAULT_HREF_ATTRIBUTE,
        link.getAttribute("href") ?? "",
      );
    }

    const defaultHref = link.getAttribute(DEFAULT_HREF_ATTRIBUTE);

    if (isCustom) {
      link.href = faviconUrl;
    } else if (defaultHref) {
      link.setAttribute("href", defaultHref);
    } else {
      link.remove();
    }
  });
};

const useBrandingTheme = () => {
  const config = useSelector(getOrganizationConfig);
  let activeColor: string | undefined = undefined;

  if (
    config.brandColors.primary !== undefined &&
    (config.brandColors.active === undefined ||
      config.brandColors.active === "")
  ) {
    const lightTheme = new LightModeTheme(config.brandColors.primary);

    activeColor =
      config.brandColors.primary === APPSMITH_BRAND_PRIMARY_COLOR
        ? getComputedStyle(document.documentElement).getPropertyValue(
            "--ads-v2-color-bg-brand-emphasis-plus",
          )
        : lightTheme.bgAccentActive.toString({ format: "hex" });
  }

  useLayoutEffect(() => {
    const cssVariables: Record<string, string> = {
      "bg-brand": config.brandColors.primary,
      "fg-brand": config.brandColors.primary,
      "border-brand": config.brandColors.primary,
      // TODO:(Albin) Remove this once branding and new DS is fully integrated
      "background-secondary": config.brandColors.background,
      "bg-brand-emphasis": config.brandColors.hover,
      "bg-brand-emphasis-plus": activeColor || config.brandColors.active,
      "fg-brand-emphasis": config.brandColors.hover,
      "border-brand-emphasis": config.brandColors.hover,
      "border-brand-emphasis-plus": activeColor || config.brandColors.active,
      "fg-on-brand": config.brandColors.font,
    };

    // Create a CSS variable for each color
    for (let i = 0; i < Object.keys(cssVariables).length; i++) {
      const key = Object.keys(cssVariables)[i];
      const value = Object.values(cssVariables)[i];
      const prefix =
        key === "background-secondary" ? "--ads-color-" : "--ads-v2-color-";

      document.documentElement.style.setProperty(`${prefix}${key}`, value);
    }

    setFavicon(getAssetUrl(config.brandFaviconUrl));
  }, [
    config.brandColors.primary,
    config.brandColors.background,
    config.brandColors.hover,
    config.brandColors.font,
    config.brandColors.disabled,
    config.brandFaviconUrl,
  ]);
};

export default useBrandingTheme;

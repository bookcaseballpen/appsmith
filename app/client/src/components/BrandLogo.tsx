import React from "react";
import { useSelector } from "react-redux";
import styled from "styled-components";
import clsx from "clsx";
import { getAssetUrl } from "ee/utils/airgapHelpers";
import { getCurrentThemeMode, ThemeMode } from "selectors/themeSelectors";
import {
  BRAND_LOGO_ON_DARK_URL,
  BRAND_LOGO_URL,
  PRODUCT_NAME,
  PRODUCT_NAME_SUFFIX,
} from "constants/Brand";

const Wrapper = styled.span<{ $height: number; $onDark: boolean }>`
  display: inline-flex;
  align-items: center;
  gap: ${({ $height }) => Math.round($height * 0.3)}px;
  color: ${({ $onDark }) =>
    $onDark ? "#ffffff" : "var(--ads-v2-color-fg-emphasis)"};
  white-space: nowrap;

  img {
    height: ${({ $height }) => $height}px;
  }

  span {
    font-size: ${({ $height }) => Math.round($height * 0.6)}px;
    font-weight: 500;
    line-height: 1;
  }
`;

interface BrandLogoProps {
  className?: string;
  /** Height of the wordmark in pixels; the text beside it scales with it. */
  height: number;
  /** The organization's logo URL; the fork's wordmark when unset or the default. */
  logoUrl?: string;
  /** Force the variant for a dark (true) or light (false) background; the theme mode decides otherwise. */
  onDark?: boolean;
}

/**
 * Fork: the product logo. The fork's "CREATIVE" wordmark carries no product name, so "App Builder" is shown beside
 * it: the colour wordmark on light backgrounds, the white one on dark ones. A custom logo is shown as it is. See
 * constants/Brand.ts.
 */
function BrandLogo(props: BrandLogoProps) {
  const themeMode = useSelector(getCurrentThemeMode);
  const onDark = props.onDark ?? themeMode === ThemeMode.DARK;
  const logoUrl = props.logoUrl ? getAssetUrl(props.logoUrl) : BRAND_LOGO_URL;

  if (logoUrl !== BRAND_LOGO_URL) {
    return (
      <img
        alt="Logo"
        className={props.className}
        src={logoUrl}
        style={{ height: props.height }}
      />
    );
  }

  return (
    <Wrapper
      $height={props.height}
      $onDark={onDark}
      className={clsx("t--brand-logo", props.className)}
      data-testid="t--brand-logo"
    >
      <img
        alt={PRODUCT_NAME}
        src={onDark ? BRAND_LOGO_ON_DARK_URL : BRAND_LOGO_URL}
      />
      <span>{PRODUCT_NAME_SUFFIX}</span>
    </Wrapper>
  );
}

export default BrandLogo;

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

// Neither part may shrink: in a tight flex row (the page header) the text was cut down to its first letter.
const Wrapper = styled.span<{ $height: number; $onDark: boolean }>`
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: ${({ $height }) => Math.round($height * 0.6)}px;
  color: ${({ $onDark }) => ($onDark ? "#ffffff" : "#4c4c4d")};
  white-space: nowrap;

  img {
    flex-shrink: 0;
    height: ${({ $height }) => $height}px;
    max-width: none;
    width: auto;
  }

  span {
    flex-shrink: 0;
    border-left: 1px solid
      ${({ $onDark }) => ($onDark ? "rgba(255, 255, 255, 0.5)" : "#d2d5d8")};
    padding-left: ${({ $height }) => Math.round($height * 0.6)}px;
    font-size: ${({ $height }) => Math.round($height * 0.75)}px;
    font-weight: 600;
    letter-spacing: 0.01em;
    line-height: ${({ $height }) => $height}px;
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
 * it, after a thin divider: the mono wordmark on light backgrounds, the white one on dark ones. A custom logo is shown
 * as it is. See constants/Brand.ts.
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

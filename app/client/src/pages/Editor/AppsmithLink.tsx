import React, { useCallback } from "react";
import { Link, Tooltip } from "@appsmith/ads";
import styled from "styled-components";
import { LOGO_TOOLTIP, createMessage } from "ee/constants/messages";
import { APPLICATIONS_URL } from "constants/routes";
// Fork: the fork's square brand icon (public/brand) in place of Appsmith's, light or dark by the theme mode.
import {
  BRAND_ICON_ON_DARK_URL,
  BRAND_ICON_URL,
  PRODUCT_NAME,
} from "constants/Brand";
import { getCurrentThemeMode, ThemeMode } from "selectors/themeSelectors";
import history from "utils/history";
import { useSelector } from "react-redux";
import { getOrganizationConfig } from "ee/selectors/organizationSelectors";
import { getCurrentWorkspaceId } from "ee/selectors/selectedWorkspaceSelectors";
import { getAssetUrl } from "ee/utils/airgapHelpers";
import { APPSMITH_BRAND_FAVICON_URL } from "utils/BrandingUtils";

export const StyledLink = styled((props) => {
  // we are removing non input related props before passing them in the components
  // eslint-disable @typescript-eslint/no-unused-vars
  return <Link {...props} />;
})`
  height: 24px;
  min-width: 24px;
  width: 24px;
  display: inline-block;

  img {
    min-width: 24px;
    width: 24px;
    height: 24px;
  }
`;

export const AppsmithLink = () => {
  const organizationConfig = useSelector(getOrganizationConfig);
  const currentWorkspaceId = useSelector(getCurrentWorkspaceId);
  const themeMode = useSelector(getCurrentThemeMode);

  const handleOnClick = useCallback(
    (e: React.MouseEvent<HTMLAnchorElement>) => {
      e.stopPropagation();

      const applicationsUrl = currentWorkspaceId
        ? `${APPLICATIONS_URL}?workspaceId=${currentWorkspaceId}`
        : APPLICATIONS_URL;

      if (e.ctrlKey || e.metaKey) {
        window.open(applicationsUrl, "_blank");
      } else {
        history.push(applicationsUrl);
      }
    },
    [currentWorkspaceId],
  );

  return (
    <Tooltip content={createMessage(LOGO_TOOLTIP)} placement="bottomLeft">
      <StyledLink onClick={handleOnClick}>
        <img
          alt={`${PRODUCT_NAME} logo`}
          className="t--appsmith-logo"
          src={
            organizationConfig.brandFaviconUrl &&
            getAssetUrl(organizationConfig.brandFaviconUrl) !==
              APPSMITH_BRAND_FAVICON_URL
              ? organizationConfig.brandFaviconUrl
              : themeMode === ThemeMode.DARK
                ? BRAND_ICON_ON_DARK_URL
                : BRAND_ICON_URL
          }
        />
      </StyledLink>
    </Tooltip>
  );
};

import React from "react";
import styled from "styled-components";
import AdminConfig from "ee/pages/AdminSettings/config";
import {
  CategoryType,
  type Category,
} from "ee/pages/AdminSettings/config/types";
import { adminSettingsCategoryUrl } from "ee/RouteBuilder";
import { useParams } from "react-router";
import AnalyticsUtil from "ee/utils/AnalyticsUtil";
import { Link, Text } from "@appsmith/ads";
import { useDispatch, useSelector } from "react-redux";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { getCurrentUser } from "selectors/usersSelectors";
import BusinessTag from "components/BusinessTag";
import EnterpriseTag from "components/EnterpriseTag";
import { getOrganizationPermissions } from "ee/selectors/organizationSelectors";
import {
  getFilteredOrgCategories,
  getFilteredUserManagementCategories,
  getFilteredInstanceCategories,
} from "ee/utils/adminSettingsHelpers";
import { useFeatureFlag } from "utils/hooks/useFeatureFlag";
import { FEATURE_FLAG } from "ee/entities/FeatureFlag";
import { getHasAuditLogsReadPermission } from "ee/utils/BusinessFeatures/permissionPageHelpers";
import { getShowAdminSettings } from "ee/utils/BusinessFeatures/adminSettingsHelpers";
import { areUpsellsHidden } from "ee/utils/forkHiddenFeatures";
import {
  FORK_SETTINGS_COLORS,
  FORK_SETTINGS_NARROW_WIDTH,
  SettingsNavSelect,
} from "ee/pages/AdminSettings/SettingsShell";

// Fork: a calm settings nav (FORK.md, "Admin settings"): small uppercase group names, no icons, no tier badges, the
// current page in the brand teal. Below 860px it turns into a select above the page (SettingsNavSelect).
export const Wrapper = styled.div`
  flex-basis: 232px;
  overflow-y: auto;
  border-right: 1px solid ${FORK_SETTINGS_COLORS.line};
  background: #ffffff;
  flex-shrink: 0;
  padding: 24px 12px 24px 24px;

  &::-webkit-scrollbar {
    display: none;
  }

  @media (max-width: ${FORK_SETTINGS_NARROW_WIDTH}px) {
    display: none;
  }
`;

export const HeaderContainer = styled.div`
  margin: 0;

  &:not(:first-child) {
    margin-top: 14px;
  }
`;

export const StyledHeader = styled(Text)`
  && {
    display: block;
    height: auto;
    padding: 0 12px 6px;
    font-size: 11px;
    font-weight: 600;
    line-height: 1.4;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: ${FORK_SETTINGS_COLORS.faint};
  }
`;

export const CategoryList = styled.ul`
  margin: 0;
  list-style-type: none;
`;

export const CategoryItem = styled.li`
  /* width: 90%; */
`;

export const StyledLink = styled(Link)<{ $active: boolean }>`
  padding: 7px 12px;
  border-radius: 6px;
  background-color: ${(props) =>
    props.$active ? FORK_SETTINGS_COLORS.tealBg : ""};
  text-decoration: none !important;

  .ads-v2-text {
    display: flex;
    gap: var(--ads-v2-spaces-3);
    align-items: center;
  }

  && {
    color: ${FORK_SETTINGS_COLORS.ink2};
  }

  &:hover {
    text-decoration: none;
  }

  &:hover:not(.active) {
    background-color: ${FORK_SETTINGS_COLORS.bg};
  }
`;

export const SettingName = styled(Text)<{ active?: boolean }>`
  && {
    font-size: 14px;
    color: ${(props) =>
      props.active ? FORK_SETTINGS_COLORS.tealInk : FORK_SETTINGS_COLORS.ink2};
    font-weight: ${(props) => (props.active ? 600 : 400)};
  }
`;

export function getSettingsCategory(type: string): Category[] {
  return Array.from(
    // TODO: Fix this the next time the file is edited
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    AdminConfig.categories.filter((cat: any) => cat.categoryType === type),
  );
}

export function Categories({
  categories,
  currentCategory,
  currentSubCategory,
  parentCategory,
  showSubCategory,
}: {
  categories?: Category[];
  parentCategory?: Category;
  currentCategory: string;
  currentSubCategory?: string;
  showSubCategory?: boolean;
}) {
  const dispatch = useDispatch();

  const triggerAnalytics = (page: string) => {
    // TODO: Fix this the next time the file is edited
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const source: any = {
      "audit-logs": "AuditLogs",
      "access-control": "AccessControl",
      provisioning: "Provisioning",
    };

    AnalyticsUtil.logEvent("ADMIN_SETTINGS_CLICK", {
      source: source[page],
    });
  };

  const onClickHandler = (category: string, showUpgradeTag: boolean) => {
    if (category === "general") {
      dispatch({
        type: ReduxActionTypes.FETCH_ADMIN_SETTINGS,
      });
    }

    if (showUpgradeTag) {
      triggerAnalytics(category);
    }
  };

  return (
    <CategoryList className="t--settings-category-list">
      {categories?.map((config) => {
        const active =
          !!currentSubCategory && showSubCategory
            ? currentSubCategory == config.slug
            : currentCategory == config.slug;
        // Fork: no lock or tier badge (ce/utils/forkHiddenFeatures.ts), and no icons in the nav.
        const showUpgradeTag =
          config?.isFeatureEnabled === false && !areUpsellsHidden();

        return (
          <CategoryItem key={config.slug}>
            <StyledLink
              $active={active}
              className={`t--settings-category-${config.slug} ${
                active ? "active" : ""
              }`}
              onClick={() =>
                onClickHandler(config.slug, showUpgradeTag || false)
              }
              startIcon={showUpgradeTag ? "lock-2-line" : undefined}
              target="_self"
              to={
                !parentCategory
                  ? adminSettingsCategoryUrl({ category: config.slug })
                  : adminSettingsCategoryUrl({
                      category: parentCategory.slug,
                      selected: config.slug,
                    })
              }
            >
              <SettingName active={active}>{config.title}</SettingName>
              {showUpgradeTag &&
                (config?.isEnterprise ? <EnterpriseTag /> : <BusinessTag />)}
            </StyledLink>
            {showSubCategory && (
              <Categories
                categories={config.children}
                currentCategory={currentCategory}
                currentSubCategory={currentSubCategory}
                parentCategory={config}
              />
            )}
          </CategoryItem>
        );
      })}
    </CategoryList>
  );
}

export default function LeftPane() {
  const profileCategories = getSettingsCategory(CategoryType.PROFILE);
  const organizationCategories = getSettingsCategory(CategoryType.ORGANIZATION);
  const userManagementCategories = getSettingsCategory(
    CategoryType.USER_MANAGEMENT,
  );
  const instanceCategories = getSettingsCategory(CategoryType.INSTANCE);
  // TODO: Fix this the next time the file is edited
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const { category, selected: subCategory } = useParams() as any;
  const user = useSelector(getCurrentUser);
  const isSuperUser = user?.isSuperUser;
  const organizationPermissions = useSelector(getOrganizationPermissions);
  const isFeatureEnabled = useFeatureFlag(FEATURE_FLAG.license_gac_enabled);
  const isAuditLogsEnabled = getHasAuditLogsReadPermission(
    isFeatureEnabled,
    organizationPermissions,
  );
  const showAdminSettings = getShowAdminSettings(isFeatureEnabled, user);

  const filteredOrgCategories = getFilteredOrgCategories(
    organizationCategories,
    isAuditLogsEnabled,
    isSuperUser,
  );

  const filteredUserManagmentCategories = getFilteredUserManagementCategories(
    userManagementCategories,
    showAdminSettings,
    isSuperUser,
  );

  const filteredInstanceCategories = getFilteredInstanceCategories(
    instanceCategories,
    isSuperUser,
  );

  return (
    <>
      {/* Fork: the same nav as a select, on narrow screens (ee/pages/AdminSettings/SettingsShell). */}
      <SettingsNavSelect
        current={category}
        groups={[
          { title: "Profile", categories: profileCategories },
          { title: "Organisation", categories: filteredOrgCategories },
          {
            title: "User management",
            categories: filteredUserManagmentCategories,
          },
          { title: "Instance", categories: filteredInstanceCategories },
        ]}
      />
      <Wrapper>
        {profileCategories.length > 0 && (
          <HeaderContainer>
            <StyledHeader kind="heading-s" renderAs="p">
              Profile
            </StyledHeader>
            <Categories
              categories={profileCategories}
              currentCategory={category}
              currentSubCategory={subCategory}
            />
          </HeaderContainer>
        )}
        {filteredOrgCategories.length > 0 && (
          <HeaderContainer>
            <StyledHeader kind="heading-s" renderAs="p">
              Organisation
            </StyledHeader>
            <Categories
              categories={filteredOrgCategories}
              currentCategory={category}
              currentSubCategory={subCategory}
            />
          </HeaderContainer>
        )}
        {filteredUserManagmentCategories.length > 0 && (
          <HeaderContainer>
            <StyledHeader kind="heading-s" renderAs="p">
              User management
            </StyledHeader>
            <Categories
              categories={filteredUserManagmentCategories}
              currentCategory={category}
              currentSubCategory={subCategory}
            />
          </HeaderContainer>
        )}
        {filteredInstanceCategories.length > 0 && (
          <HeaderContainer>
            <StyledHeader kind="heading-s" renderAs="p">
              Instance
            </StyledHeader>
            <Categories
              categories={filteredInstanceCategories}
              currentCategory={category}
              currentSubCategory={subCategory}
            />
          </HeaderContainer>
        )}
      </Wrapper>
    </>
  );
}

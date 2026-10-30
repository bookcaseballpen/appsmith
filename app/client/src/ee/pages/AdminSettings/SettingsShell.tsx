import React from "react";
import styled from "styled-components";
import type { Category } from "ee/pages/AdminSettings/config/types";
import { adminSettingsCategoryUrl } from "ee/RouteBuilder";
import history from "utils/history";

/**
 * Fork: the admin settings shell (FORK.md, "Admin settings"): the home page's header, a calm left nav, each page with
 * a title, a subtitle and its fields in a card, and a sticky footer with Reset and Save. Below
 * FORK_SETTINGS_NARROW_WIDTH the nav becomes a select above the page and the fields stack.
 */
export const FORK_SETTINGS_NARROW_WIDTH = 860;

export const FORK_SETTINGS_COLORS = {
  ink: "#1f2328",
  ink2: "#4c4c4d",
  muted: "#6b7177",
  faint: "#9aa0a6",
  line: "#e4e6e8",
  line2: "#d2d5d8",
  bg: "#f6f7f8",
  tealInk: "#00747d",
  tealBg: "#e6f6f7",
};

/** Subtitles for the settings pages that upstream gives none. */
const SETTINGS_SUBTITLES: Record<string, string> = {
  email:
    "Outgoing mail for password resets, email verification and invitations.",
  "instance-settings": "Admins, custom domain and API keys of this instance.",
  configuration: "Database, base URL and embedding rules of this instance.",
  version: "The version this instance runs.",
};

export const getSettingsSubtitle = (slug: string, subText?: string) =>
  subText || SETTINGS_SUBTITLES[slug];

export interface SettingsNavGroup {
  title: string;
  categories: Category[];
}

const NavSelectWrapper = styled.div`
  display: none;
  padding: 14px 14px 0;
  background: ${FORK_SETTINGS_COLORS.bg};

  select {
    width: 100%;
    height: 40px;
    padding: 0 10px;
    border: 1px solid ${FORK_SETTINGS_COLORS.line2};
    border-radius: 8px;
    background: #ffffff;
    color: ${FORK_SETTINGS_COLORS.ink};
    font: inherit;
  }

  @media (max-width: ${FORK_SETTINGS_NARROW_WIDTH}px) {
    display: block;
  }
`;

/** The settings nav as a select, shown instead of the left nav on narrow screens. */
export function SettingsNavSelect(props: {
  current?: string;
  groups: SettingsNavGroup[];
}) {
  const groups = props.groups.filter((group) => group.categories.length > 0);

  return (
    <NavSelectWrapper>
      <select
        aria-label="Settings section"
        data-testid="t--settings-nav-select"
        onChange={(event) =>
          history.push(
            adminSettingsCategoryUrl({ category: event.target.value }),
          )
        }
        value={props.current}
      >
        {groups.map((group) => (
          <optgroup key={group.title} label={group.title}>
            {group.categories.map((category) => (
              <option key={category.slug} value={category.slug}>
                {category.title}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
    </NavSelectWrapper>
  );
}

/** The sticky footer under a settings page (Reset, Save). */
export const SettingsFooter = styled.div`
  position: fixed;
  bottom: 0;
  right: 0;
  width: calc(100% - 232px);
  height: ${(props) => props.theme.settings.footerHeight}px;
  padding: 12px 32px;
  border-top: 1px solid ${FORK_SETTINGS_COLORS.line};
  z-index: 2;
  background-color: #ffffff;
  display: flex;
  flex-direction: row;
  gap: 10px;
  align-items: center;

  @media (max-width: ${FORK_SETTINGS_NARROW_WIDTH}px) {
    width: 100%;
    height: auto;
    padding: 10px 14px;
    flex-wrap: wrap;
  }
`;

/** Padding for a card whose content is not the settings form's rows (the Account page). */
export const SettingsCardBody = styled.div`
  padding: 4px 20px 20px;
`;

/**
 * The card around a settings page's fields: one row per field, the label on the left and the field with its help
 * text on the right (stacked on narrow screens).
 */
export const SettingsFormCard = styled.div`
  max-width: 680px;
  overflow: hidden;
  background: #ffffff;
  border: 1px solid ${FORK_SETTINGS_COLORS.line};
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05);

  /* The rows of the page's top-level group; each ends with a line, the card hides the last one. */
  > [data-testid="admin-settings-group-wrapper"] > div:last-child {
    margin-bottom: -1px;

    > div:not(.hide) {
      padding: 16px 20px;
      border-bottom: 1px solid ${FORK_SETTINGS_COLORS.line};
    }

    > div.callout-link {
      margin: 0;
    }
  }

  [data-testid="admin-settings-form-group"] {
    width: auto;
    margin-bottom: 0;
  }

  @media (min-width: ${FORK_SETTINGS_NARROW_WIDTH + 1}px) {
    [data-testid="admin-settings-form-group"] {
      display: grid;
      grid-template-columns: 200px minmax(0, 1fr);
      column-gap: 24px;
      align-items: start;

      > * {
        grid-column: 2;
      }

      > .styled-label {
        grid-column: 1;
        grid-row: 1 / span 3;
        padding-top: 8px;
      }
    }
  }

  .admin-settings-form-group-label {
    font-weight: 600;
    color: ${FORK_SETTINGS_COLORS.ink};
  }

  [data-testid="admin-settings-form-group-subtext"] {
    color: ${FORK_SETTINGS_COLORS.muted};
    font-size: 12.5px;
    margin-top: 5px;
  }
`;

import styled from "styled-components";
import { Classes } from "@blueprintjs/core";
import { Link, Text } from "@appsmith/ads";
import {
  FORK_SETTINGS_COLORS,
  FORK_SETTINGS_NARROW_WIDTH,
} from "ee/pages/AdminSettings/SettingsShell";

// Fork: the settings shell's page (ee/pages/AdminSettings/SettingsShell): a 22px title, a muted subtitle, then the
// fields in a card.
export const Wrapper = styled.div`
  flex-basis: calc(100% - ${(props) => props.theme.homePage.leftPane.width}px);
  flex-grow: 1;
  min-width: 0;
  padding: 28px 32px 24px;

  /* 84px is the height of save bottom bar; use 100% so banner offset on the
     parent PageWrapper does not need to be duplicated here. */
  height: calc(100% - 84px);
  overflow: auto;

  @media (max-width: ${FORK_SETTINGS_NARROW_WIDTH}px) {
    padding: 18px 14px;
  }
`;

export const HeaderWrapper = styled.div<{ margin?: string }>`
  margin-bottom: ${(props) => props.margin ?? `24px`};
`;

export const SettingsHeader = styled(Text)`
  && {
    margin-bottom: 4px;
    font-size: 22px;
    font-weight: 600;
    letter-spacing: -0.01em;
    color: ${FORK_SETTINGS_COLORS.ink};
  }
`;

export const SettingsSubHeader = styled(Text)`
  && {
    color: ${FORK_SETTINGS_COLORS.muted};
  }
`;

export const SettingsFormWrapper = styled.div`
  max-width: 680px;
  .openid_tag {
    .${Classes.TAG_REMOVE} {
      display: none;
    }
  }
`;

export const BottomSpace = styled.div`
  height: ${(props) => props.theme.settings.footerHeight + 20}px;
`;

export const ContentWrapper = styled.div``;

export const LoaderContainer = styled.div`
  height: 100%;
  display: flex;
  justify-content: center;
  width: 100%;
  align-items: center;
`;

export const ContentBox = styled.div`
  border-radius: var(--ads-v2-border-radius);
  border-color: var(--ads-v2-color-border);

  .business-tag {
    width: fit-content;
  }

  .hover-state {
    border-radius: var(--ads-v2-border-radius);
    background-color: var(--ads-v2-color-bg-subtle);
  }
`;

export const HelperText = styled(Text)`
  font-size: 12px;
  color: var(--ads-v2-color-fg-muted);
`;

export const NoUnderLineLink = styled(Link)`
  text-decoration: none !important;
`;

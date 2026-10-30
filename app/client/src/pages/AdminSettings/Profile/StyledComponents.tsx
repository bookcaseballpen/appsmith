import { Button, Text } from "@appsmith/ads";
import styled from "styled-components";
import { SettingsFooter } from "ee/pages/AdminSettings/SettingsShell";

export const FieldWrapper = styled.div`
  /* width: 460px; */
  /* display: flex; */
  .user-profile-image-picker {
    width: 166px;
    margin-top: 4px;
  }
`;

export const LabelWrapper = styled.div`
  .self-center {
    align-self: center;
  }
  /* width: 240px; */
  /* display: flex; */
  color: var(--ads-v2-color-fg);
  /* font-size: 14px; */
`;

export const Loader = styled.div`
  height: 38px;
  width: 320px;
  border-radius: 0;
`;

export const TextLoader = styled.div`
  height: 15px;
  width: 320px;
  border-radius: 0;
`;

export const ResetPasswordButton = styled(Button)`
  width: fit-content;
`;

export const SubCategory = styled(Text)`
  font-weight: 600;
  color: var(--ads-v2-color-fg-emphasis);
  margin-bottom: 12px;
  margin-top: 24px;
`;

// Fork: the settings shell's footer (ee/pages/AdminSettings/SettingsShell); Save is the first child, on the right.
export const SettingsButtonWrapper = styled(SettingsFooter)`
  flex-direction: row-reverse;
`;

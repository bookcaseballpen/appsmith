import React from "react";
import styled from "styled-components";
import { useSelector } from "react-redux";
import { Icon, Text } from "@appsmith/ads";
import {
  EDITOR_READ_ONLY_BANNER,
  selectIsEditorReadOnly,
} from "ee/utils/editorReadOnly";

export const EDITOR_READ_ONLY_BANNER_HEIGHT = 28;

const Banner = styled.div`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ads-v2-spaces-2);
  height: ${EDITOR_READ_ONLY_BANNER_HEIGHT}px;
  background-color: var(--ads-v2-color-bg-information);
  border-bottom: 1px solid var(--ads-v2-color-border);
`;

/** Fork: tells everyone in the editor that apps are changed through the projects repo (ee/utils/editorReadOnly). */
export function EditorReadOnlyBanner() {
  const isEditorReadOnly = useSelector(selectIsEditorReadOnly);

  if (!isEditorReadOnly) {
    return null;
  }

  return (
    <Banner data-testid="t--editor-read-only-banner" role="status">
      <Icon name="lock-2-line" size="sm" />
      <Text kind="body-s">{EDITOR_READ_ONLY_BANNER}</Text>
    </Banner>
  );
}

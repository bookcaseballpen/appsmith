import React, { useMemo } from "react";
import { useSelector } from "react-redux";
import { createMessage, EDITOR_PANE_TEXTS } from "ee/constants/messages";
import { EditorEntityTab } from "IDE/Interfaces/EditorTypes";
import { useSegmentNavigation } from "./useSegmentNavigation";
import { useCurrentEditorState } from "../../../hooks/useCurrentEditorState";
import { EditorSegments } from "@appsmith/ads";
import { selectIsEditorReadOnly } from "ee/utils/editorReadOnly";

const SegmentSwitcher = () => {
  const { segment } = useCurrentEditorState();
  const { onSegmentChange } = useSegmentNavigation();
  // Fork: the read-only editor has no UI tab; widgets are not edited here.
  const isEditorReadOnly = useSelector(selectIsEditorReadOnly);

  const segmentOptions = useMemo(() => {
    const options = [
      {
        label: createMessage(EDITOR_PANE_TEXTS.queries_tab),
        startIcon: "queries-line",
        value: EditorEntityTab.QUERIES,
      },
      {
        label: createMessage(EDITOR_PANE_TEXTS.js_tab),
        startIcon: "content-type-json",
        value: EditorEntityTab.JS,
      },
      {
        label: createMessage(EDITOR_PANE_TEXTS.ui_tab),
        startIcon: "dashboard-line",
        value: EditorEntityTab.UI,
      },
    ];

    return isEditorReadOnly
      ? options.filter((option) => option.value !== EditorEntityTab.UI)
      : options;
  }, [isEditorReadOnly]);

  return (
    <EditorSegments
      onSegmentChange={onSegmentChange}
      options={segmentOptions}
      selectedSegment={segment}
    />
  );
};

export default SegmentSwitcher;

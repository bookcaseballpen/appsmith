import React from "react";
import { Flex, Text } from "@appsmith/ads";
import { Switch, useRouteMatch } from "react-router";
import {
  ADD_PATH,
  BUILDER_CUSTOM_PATH,
  BUILDER_PATH,
  BUILDER_PATH_DEPRECATED,
  BUILDER_PATH_STATIC,
  WIDGETS_EDITOR_BASE_PATH,
  WIDGETS_EDITOR_ID_PATH,
} from "constants/routes";
import ListWidgets from "../../../components/UIList/UIList";
import AddWidgets from "../../../components/WidgetAdd";
import { useSelector } from "react-redux";
import { getPagePermissions } from "selectors/editorSelectors";
import { useFeatureFlag } from "utils/hooks/useFeatureFlag";
import { FEATURE_FLAG } from "ee/entities/FeatureFlag";
import { getHasManagePagePermission } from "ee/utils/BusinessFeatures/permissionPageHelpers";
import { DEFAULT_EXPLORER_PANE_WIDTH } from "constants/AppConstants";
import { SentryRoute } from "components/SentryRoute";
import {
  EDITOR_READ_ONLY_WIDGETS_NOTE,
  selectIsEditorReadOnly,
} from "ee/utils/editorReadOnly";

const UISegment = () => {
  const { path } = useRouteMatch();
  const [focusSearchInput, setFocusSearchInput] = React.useState(false);

  const pagePermissions = useSelector(getPagePermissions);
  const isFeatureEnabled = useFeatureFlag(FEATURE_FLAG.license_gac_enabled);

  const canManagePages = getHasManagePagePermission(
    isFeatureEnabled,
    pagePermissions,
  );
  const isEditorReadOnly = useSelector(selectIsEditorReadOnly);

  // Fork: the read-only editor shows no widget list or widget explorer, only what the canvas is for.
  if (isEditorReadOnly) {
    return (
      <Flex
        className="ide-editor-left-pane__content-widgets"
        data-testid="t--editor-read-only-widgets-note"
        padding="spaces-4"
        width={DEFAULT_EXPLORER_PANE_WIDTH - 1 + "px"}
      >
        <Text kind="body-s">{EDITOR_READ_ONLY_WIDGETS_NOTE}</Text>
      </Flex>
    );
  }

  if (!canManagePages) {
    return <ListWidgets setFocusSearchInput={setFocusSearchInput} />;
  }

  return (
    <Flex
      className="ide-editor-left-pane__content-widgets"
      flexDirection="column"
      gap="spaces-3"
      overflow="hidden"
      width={DEFAULT_EXPLORER_PANE_WIDTH - 1 + "px"}
    >
      <Switch>
        <SentryRoute
          exact
          path={[
            BUILDER_PATH_DEPRECATED,
            BUILDER_PATH,
            BUILDER_CUSTOM_PATH,
            BUILDER_PATH_STATIC,
            `${path}${WIDGETS_EDITOR_ID_PATH}${ADD_PATH}`,
          ]}
        >
          <AddWidgets focusSearchInput={focusSearchInput} />
        </SentryRoute>
        <SentryRoute
          exact
          path={[
            `${path}${WIDGETS_EDITOR_BASE_PATH}`,
            `${path}${WIDGETS_EDITOR_ID_PATH}`,
          ]}
        >
          <ListWidgets setFocusSearchInput={setFocusSearchInput} />
        </SentryRoute>
      </Switch>
    </Flex>
  );
};

export default UISegment;

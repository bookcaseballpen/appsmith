import React from "react";
import { Switch, useRouteMatch, type RouteProps } from "react-router";
import { MainPaneRoutes } from "ee/pages/AppIDE/layouts/routers/MainPane/constants";
import { useSelector } from "react-redux";
import {
  selectCanvasOnlyMode,
  selectCombinedPreviewMode,
} from "selectors/gitModSelectors";
import WidgetsEditor from "pages/Editor/WidgetsEditor";
import { useWidgetSelectionBlockListener } from "../../hooks/useWidgetSelectionBlockListener";
import { SentryRoute } from "components/SentryRoute";
import { getCurrentUser } from "selectors/usersSelectors";
import { getInstanceRole, isAdminRole } from "ee/utils/instanceRoles";

/** Fork: the main pane routes of the datasource pages (MainPaneRoutes keys). */
export const DATASOURCE_ROUTE_KEYS = [
  "Datasource Create and Active",
  "DatasourceEditor",
  "DatasourceBlankState",
  "SAASDatasourceEditor",
];

export const MainPane = (props: { id: string }) => {
  const { path } = useRouteMatch();
  const routes: Array<RouteProps & { key: string }> = MainPaneRoutes(path);

  useWidgetSelectionBlockListener();

  const isPreviewMode = useSelector(selectCombinedPreviewMode);
  // Fork: the read-only editor (ee/utils/editorReadOnly) shows only the running canvas, but Admins still add and
  // change datasources here: the datasource pages open for them, everything else stays the canvas. Preview mode,
  // protected branches and Approvers (selectCanvasOnlyMode) keep the canvas only. See FORK.md, "Read-only editor".
  const isCanvasOnlyMode = useSelector(selectCanvasOnlyMode);
  const isAdmin = isAdminRole(getInstanceRole(useSelector(getCurrentUser)));
  const opensDatasourcePages = isPreviewMode && !isCanvasOnlyMode && isAdmin;

  return (
    <div
      className="relative flex flex-col flex-1 overflow-auto z-2 h-full"
      data-testid="t--ide-main-pane"
      id={props.id}
    >
      {isPreviewMode && !opensDatasourcePages ? (
        <WidgetsEditor />
      ) : (
        <Switch>
          {(opensDatasourcePages
            ? routes.filter((route) =>
                DATASOURCE_ROUTE_KEYS.includes(route.key),
              )
            : routes
          ).map((route) => (
            <SentryRoute {...route} key={route.key} />
          ))}
          {opensDatasourcePages && (
            <SentryRoute component={WidgetsEditor} key="ReadOnlyCanvas" />
          )}
        </Switch>
      )}
    </div>
  );
};

export default MainPane;

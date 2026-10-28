import { fork, put, select } from "redux-saga/effects";
import type { RouteChangeActionPayload } from "actions/focusHistoryActions";
import { FocusEntity, identifyEntityFromPath } from "navigation/FocusEntity";
import log from "loglevel";
import { type ReduxAction } from "actions/ReduxActionTypes";
import { getCurrentThemeDetails } from "selectors/themeSelectors";
import type { BackgroundTheme } from "sagas/ThemeSaga";
import { changeAppBackground } from "sagas/ThemeSaga";
import { updateRecentEntitySaga } from "sagas/GlobalSearchSagas";
import {
  setLastSelectedWidget,
  setSelectedWidgets,
} from "actions/widgetSelectionActions";
import { MAIN_CONTAINER_WIDGET_ID } from "constants/WidgetConstants";
import FocusRetention from "sagas/FocusRetentionSaga";
import { getSafeCrash } from "selectors/errorSelectors";
import { flushErrors } from "actions/errorActions";
import type { NavigationMethod } from "utils/history";
import { getIDETypeByUrl } from "ee/entities/IDE/utils";
import { IDE_TYPE } from "ee/IDE/Interfaces/IDETypes";
import { updateIDETabsOnRouteChangeSaga } from "sagas/IDESaga";

let previousPath: string;

export function* handleRouteChange(
  action: ReduxAction<RouteChangeActionPayload>,
) {
  const { pathname, state } = action.payload.location;

  try {
    yield fork(clearErrors);
    const IDEType = getIDETypeByUrl(pathname);

    if (previousPath) {
      yield fork(
        FocusRetention.onRouteChange.bind(FocusRetention),
        pathname,
        previousPath,
        state,
      );
    }

    if (IDEType === IDE_TYPE.App) {
      yield fork(appBackgroundHandler);
      const entityInfo = identifyEntityFromPath(pathname);

      yield fork(updateRecentEntitySaga, entityInfo);
      yield fork(updateIDETabsOnRouteChangeSaga, entityInfo);
      yield fork(setSelectedWidgetsSaga, state?.invokedBy);
    }
  } catch (e) {
    log.error("Error in focus change", e);
  } finally {
    previousPath = pathname;
  }
}

function* appBackgroundHandler() {
  const currentTheme: BackgroundTheme = yield select(getCurrentThemeDetails);

  changeAppBackground(currentTheme);
}

/**
 * When an error occurs, we take over the whole router and keep it the error
 * state till the errors are flushed. By default, we will flush out the
 * error state when a CTA on the page is clicked but in case the
 * user navigates via the browser buttons, this will ensure
 * the errors are flushed
 * */
function* clearErrors() {
  const isCrashed: boolean = yield select(getSafeCrash);

  if (isCrashed) {
    yield put(flushErrors());
  }
}

export function* setSelectedWidgetsSaga(invokedBy?: NavigationMethod) {
  const pathname = window.location.pathname;
  const entityInfo = identifyEntityFromPath(pathname);
  let widgets: string[] = [];
  let lastSelectedWidget = MAIN_CONTAINER_WIDGET_ID;

  if (entityInfo.entity === FocusEntity.WIDGET) {
    widgets = entityInfo.id.split(",");

    if (widgets.length) {
      lastSelectedWidget = widgets[widgets.length - 1];
    }
  }

  yield put(setSelectedWidgets(widgets, invokedBy));
  yield put(setLastSelectedWidget(lastSelectedWidget));
}

export * from "ce/sagas/ApiCallerSagas";
import {
  updateActionAPICall as CE_updateActionAPICall,
  updateJSCollectionAPICall as CE_updateJSCollectionAPICall,
} from "ce/sagas/ApiCallerSagas";
import { select, take } from "redux-saga/effects";
import type { ApiResponse } from "api/ApiResponses";
import type { Action } from "entities/Action";
import type { JSAction, JSCollection } from "entities/JSCollection";
import {
  ReduxActionErrorTypes,
  ReduxActionTypes,
} from "ee/constants/ReduxActionConstants";
import { getFeatureFlagsFetched } from "selectors/usersSelectors";
import { selectIsEditorReadOnly } from "ee/utils/editorReadOnly";

// Fork: in the read-only editor the client still syncs what it derives on load (a JS
// object's parsed functions, action settings) through these calls. The server refuses
// them, so keep the change in the client and don't send it.
const keptInClient = <T>(data: T): ApiResponse<T> => ({
  responseMeta: { status: 200, success: true },
  data,
});

// Until the feature flags have loaded the switch is unknown, so wait for them rather than
// send a sync the server may refuse (a 403 toast on load).
function* isReadOnlyOnceFlagsLoaded() {
  const flagsFetched: boolean = yield select(getFeatureFlagsFetched);

  if (!flagsFetched) {
    yield take([
      ReduxActionTypes.FETCH_FEATURE_FLAGS_SUCCESS,
      ReduxActionErrorTypes.FETCH_FEATURE_FLAGS_ERROR,
    ]);
  }

  const readOnly: boolean = yield select(selectIsEditorReadOnly);

  return readOnly;
}

export function* updateActionAPICall(action: Action) {
  if (yield* isReadOnlyOnceFlagsLoaded()) return keptInClient(action);

  return yield* CE_updateActionAPICall(action);
}

export function* updateJSCollectionAPICall(
  jsCollection: JSCollection,
  newActions?: Partial<JSAction>[],
  updatedActions?: JSAction[],
  deletedActions?: JSAction[],
) {
  if (yield* isReadOnlyOnceFlagsLoaded()) return keptInClient(jsCollection);

  return yield* CE_updateJSCollectionAPICall(
    jsCollection,
    newActions,
    updatedActions,
    deletedActions,
  );
}

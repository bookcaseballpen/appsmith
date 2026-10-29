import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { select, takeEvery } from "redux-saga/effects";
import {
  selectIsEditorReadOnly,
  setEditorReadOnly,
} from "ee/utils/editorReadOnly";

/** Fork: copies the read-only editor flag for the permission helpers (see ee/utils/editorReadOnly). */
export function* syncEditorReadOnlySaga() {
  const readOnly: boolean = yield select(selectIsEditorReadOnly);

  setEditorReadOnly(readOnly);
}

export default function* editorReadOnlySagas() {
  yield takeEvery(
    [
      ReduxActionTypes.FETCH_FEATURE_FLAGS_SUCCESS,
      ReduxActionTypes.FETCH_OVERRIDDEN_FEATURE_FLAGS,
      ReduxActionTypes.UPDATE_OVERRIDDEN_FEATURE_FLAGS,
    ],
    syncEditorReadOnlySaga,
  );
}

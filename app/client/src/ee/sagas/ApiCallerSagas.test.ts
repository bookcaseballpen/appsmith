import JSActionAPI from "ee/api/JSActionAPI";
import ActionAPI from "api/ActionAPI";
import { select, take } from "redux-saga/effects";
import type { Action } from "entities/Action";
import type { JSCollection } from "entities/JSCollection";
import {
  ReduxActionErrorTypes,
  ReduxActionTypes,
} from "ee/constants/ReduxActionConstants";
import { getFeatureFlagsFetched } from "selectors/usersSelectors";
import { selectIsEditorReadOnly } from "ee/utils/editorReadOnly";
import {
  updateActionAPICall,
  updateJSCollectionAPICall,
} from "./ApiCallerSagas";

jest.mock("ee/api/JSActionAPI", () => ({
  __esModule: true,
  default: { updateJSCollection: jest.fn(() => "js-request") },
}));
jest.mock("api/ActionAPI", () => ({
  __esModule: true,
  default: { updateAction: jest.fn(() => "action-request") },
}));

const collection = { id: "js1", name: "Utils" } as JSCollection;
const action = { id: "a1", name: "GetUsers" } as Action;
// The sagas are stepped by hand, feeding each effect's result, so loosen what next() takes.
const jsSync = () => updateJSCollectionAPICall(collection) as Generator;
const actionSync = () => updateActionAPICall(action) as Generator;
const kept = <T>(data: T) => ({
  done: true,
  value: { responseMeta: { status: 200, success: true }, data },
});

describe("fork read-only editor API caller sagas", () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it("keep JS object and action syncs in the client when read-only", () => {
    const jsSaga = jsSync();

    expect(jsSaga.next().value).toEqual(select(getFeatureFlagsFetched));
    expect(jsSaga.next(true).value).toEqual(select(selectIsEditorReadOnly));
    expect(jsSaga.next(true)).toEqual(kept(collection));

    const actionSaga = actionSync();

    actionSaga.next();
    actionSaga.next(true);
    expect(actionSaga.next(true)).toEqual(kept(action));
    expect(JSActionAPI.updateJSCollection).not.toHaveBeenCalled();
    expect(ActionAPI.updateAction).not.toHaveBeenCalled();
  });

  it("wait for the feature flags before deciding, so no early sync reaches the server", () => {
    const jsSaga = jsSync();

    jsSaga.next();
    expect(jsSaga.next(false).value).toEqual(
      take([
        ReduxActionTypes.FETCH_FEATURE_FLAGS_SUCCESS,
        ReduxActionErrorTypes.FETCH_FEATURE_FLAGS_ERROR,
      ]),
    );
    expect(JSActionAPI.updateJSCollection).not.toHaveBeenCalled();
    expect(jsSaga.next().value).toEqual(select(selectIsEditorReadOnly));
    expect(jsSaga.next(true)).toEqual(kept(collection));
    expect(JSActionAPI.updateJSCollection).not.toHaveBeenCalled();
  });

  it("send them to the server otherwise", () => {
    const jsSaga = jsSync();
    const jsResponse = {
      responseMeta: { status: 200, success: true },
      data: collection,
    };

    jsSaga.next();
    jsSaga.next(true);
    expect(jsSaga.next(false).value).toBe("js-request");
    expect(jsSaga.next(jsResponse)).toEqual({ done: true, value: jsResponse });

    const actionSaga = actionSync();

    actionSaga.next();
    actionSaga.next(true);
    expect(actionSaga.next(false).value).toBe("action-request");
    expect(JSActionAPI.updateJSCollection).toHaveBeenCalledWith(
      collection,
      undefined,
      undefined,
      undefined,
    );
    expect(ActionAPI.updateAction).toHaveBeenCalledWith(action);
  });
});

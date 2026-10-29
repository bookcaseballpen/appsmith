import JSActionAPI from "ee/api/JSActionAPI";
import ActionAPI from "api/ActionAPI";
import type { Action } from "entities/Action";
import type { JSCollection } from "entities/JSCollection";
import { setEditorReadOnly } from "ee/utils/editorReadOnly";
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

describe("fork read-only editor API caller sagas", () => {
  afterEach(() => {
    setEditorReadOnly(false);
    jest.clearAllMocks();
  });

  it("keep JS object and action syncs in the client when read-only", () => {
    setEditorReadOnly(true);

    expect(updateJSCollectionAPICall(collection).next()).toEqual({
      done: true,
      value: { responseMeta: { status: 200, success: true }, data: collection },
    });
    expect(updateActionAPICall(action).next()).toEqual({
      done: true,
      value: { responseMeta: { status: 200, success: true }, data: action },
    });
    expect(JSActionAPI.updateJSCollection).not.toHaveBeenCalled();
    expect(ActionAPI.updateAction).not.toHaveBeenCalled();
  });

  it("send them to the server otherwise", () => {
    const jsSaga = updateJSCollectionAPICall(collection);
    const jsResponse = {
      responseMeta: { status: 200, success: true },
      data: collection,
    };

    expect(jsSaga.next().value).toBe("js-request");
    expect(jsSaga.next(jsResponse)).toEqual({ done: true, value: jsResponse });

    const actionSaga = updateActionAPICall(action);

    expect(actionSaga.next().value).toBe("action-request");
    expect(JSActionAPI.updateJSCollection).toHaveBeenCalledWith(
      collection,
      undefined,
      undefined,
      undefined,
    );
    expect(ActionAPI.updateAction).toHaveBeenCalledWith(action);
  });
});

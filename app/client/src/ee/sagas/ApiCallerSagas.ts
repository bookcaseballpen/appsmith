export * from "ce/sagas/ApiCallerSagas";
import {
  updateActionAPICall as CE_updateActionAPICall,
  updateJSCollectionAPICall as CE_updateJSCollectionAPICall,
} from "ce/sagas/ApiCallerSagas";
import type { ApiResponse } from "api/ApiResponses";
import type { Action } from "entities/Action";
import type { JSAction, JSCollection } from "entities/JSCollection";
import { isEditorReadOnly } from "ee/utils/editorReadOnly";

// Fork: in the read-only editor the client still syncs what it derives on load (a JS
// object's parsed functions, action settings) through these calls. The server refuses
// them, so keep the change in the client and don't send it.
const keptInClient = <T>(data: T): ApiResponse<T> => ({
  responseMeta: { status: 200, success: true },
  data,
});

export function* updateActionAPICall(action: Action) {
  if (isEditorReadOnly()) return keptInClient(action);

  return yield* CE_updateActionAPICall(action);
}

export function* updateJSCollectionAPICall(
  jsCollection: JSCollection,
  newActions?: Partial<JSAction>[],
  updatedActions?: JSAction[],
  deletedActions?: JSAction[],
) {
  if (isEditorReadOnly()) return keptInClient(jsCollection);

  return yield* CE_updateJSCollectionAPICall(
    jsCollection,
    newActions,
    updatedActions,
    deletedActions,
  );
}

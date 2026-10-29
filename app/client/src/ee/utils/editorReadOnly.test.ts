import type { DefaultRootState } from "react-redux";
import store from "store";
import { select } from "redux-saga/effects";
import { EditorViewMode } from "IDE/Interfaces/EditorTypes";
import { getIDEViewMode } from "selectors/ideSelectors";
import {
  selectCanvasOnlyMode,
  selectCombinedPreviewMode,
} from "selectors/gitModSelectors";
import { syncEditorReadOnlySaga } from "ee/sagas/editorReadOnlySagas";
import {
  isEditorReadOnly,
  selectIsEditorReadOnly,
  setEditorReadOnly,
} from "./editorReadOnly";

const stateWith = ({
  isPreviewMode = false,
  overridden,
  readOnly,
}: {
  isPreviewMode?: boolean;
  overridden?: boolean;
  readOnly?: boolean;
}): DefaultRootState => {
  const state = store.getState();

  return {
    ...state,
    ui: {
      ...state.ui,
      editor: { ...state.ui.editor, isPreviewMode },
      users: {
        ...state.ui.users,
        featureFlag: {
          ...state.ui.users.featureFlag,
          data: {
            ...state.ui.users.featureFlag.data,
            ...(readOnly === undefined
              ? {}
              : { fork_editor_read_only: readOnly }),
          },
          overriddenFlags:
            overridden === undefined
              ? {}
              : { fork_editor_read_only: overridden },
        },
      },
    },
  } as DefaultRootState;
};

describe("read-only editor switch", () => {
  afterEach(() => setEditorReadOnly(false));

  it("reads the flag the server sends", () => {
    expect(selectIsEditorReadOnly(stateWith({ readOnly: true }))).toBe(true);
    expect(selectIsEditorReadOnly(stateWith({ readOnly: false }))).toBe(false);
  });

  it("is off when the server sends no flag", () => {
    expect(selectIsEditorReadOnly(stateWith({}))).toBe(false);
    expect(selectIsEditorReadOnly({} as DefaultRootState)).toBe(false);
  });

  it("lets an overridden flag win", () => {
    expect(
      selectIsEditorReadOnly(stateWith({ readOnly: true, overridden: false })),
    ).toBe(false);
  });

  it("copies the flag for the permission helpers", () => {
    const saga = syncEditorReadOnlySaga();

    expect(saga.next().value).toEqual(select(selectIsEditorReadOnly));
    saga.next(true);
    expect(isEditorReadOnly()).toBe(true);
  });

  it("runs the canvas as in preview but keeps the IDE panes", () => {
    const state = stateWith({ readOnly: true });

    expect(selectCombinedPreviewMode(state)).toBe(true);
    expect(selectCanvasOnlyMode(state)).toBe(false);
    expect(getIDEViewMode(state)).toBe(EditorViewMode.SplitScreen);
  });

  it("leaves the editor as upstream when off", () => {
    const state = stateWith({ readOnly: false });

    expect(selectCombinedPreviewMode(state)).toBe(false);
    expect(getIDEViewMode(state)).toBe(state.ui.ide.view);
    expect(
      selectCombinedPreviewMode(
        stateWith({ readOnly: false, isPreviewMode: true }),
      ),
    ).toBe(true);
  });
});

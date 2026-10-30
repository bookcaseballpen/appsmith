import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { type ReduxAction } from "actions/ReduxActionTypes";
import { APP_MODE } from "entities/App";
import AppEngineFactory from "entities/Engine/factory";
import {
  getInitResponses,
  isEditModeRefused,
  startAppEngine,
  executeActionDuringUserDetailsInitialisation,
} from "sagas/InitSagas";
import { toast } from "@appsmith/ads";
import { NO_EDIT_ACCESS_MESSAGE } from "ee/utils/instanceRoles";
import type { AppEnginePayload } from "entities/Engine";
import { testSaga } from "redux-saga-test-plan";
import { generateAutoHeightLayoutTreeAction } from "actions/autoHeightActions";
import mockResponse from "./mockConsolidatedApiResponse.json";
import { startRootSpan } from "instrumentation/generateTraces";
import { getCurrentUser as getCurrentUserSelector } from "selectors/usersSelectors";
import { DefaultCurrentUserDetails } from "constants/userConstants";
import history from "utils/history";

jest.mock("../../api/Api", () => ({
  __esModule: true,
  default: class Api {},
}));

// Mock the entire AppEngineFactory module
jest.mock("entities/Engine/factory", () => ({
  __esModule: true,

  default: class AppEngineFactory {
    static create = jest.fn();
  },
}));

describe("tests the sagas in initSagas", () => {
  const pageId = "pageId";
  const basePageId = "basePageId";
  const action: ReduxAction<AppEnginePayload> = {
    type: ReduxActionTypes.INITIALIZE_EDITOR,
    payload: {
      mode: APP_MODE.EDIT,
      basePageId: basePageId,
      applicationId: "applicationId",
    },
  };

  it("tests the order of execute in startAppEngine", () => {
    const engine = {
      startPerformanceTracking: jest.fn(),
      setupEngine: jest.fn(),
      loadAppData: jest.fn().mockResolvedValue({
        applicationId: action.payload.applicationId,
        toLoadPageId: pageId,
        toLoadBasePageId: action.payload.basePageId,
      }),
      loadAppURL: jest.fn(),
      loadAppEntities: jest.fn(),
      loadGit: jest.fn(),
      completeChore: jest.fn(),
      stopPerformanceTracking: jest.fn(),
    };

    const mockRootSpan = startRootSpan("startAppEngine");

    (AppEngineFactory.create as jest.Mock).mockReturnValue(engine);

    testSaga(startAppEngine, action)
      .next()
      .call(engine.setupEngine, action.payload, mockRootSpan)
      .next()
      .call(getInitResponses, { ...action.payload })
      .next(mockResponse.data)
      .select(getCurrentUserSelector)
      .next({ ...DefaultCurrentUserDetails, role: "DEV" })
      .put({ type: ReduxActionTypes.LINT_SETUP })
      .next()
      .call(engine.loadAppData, action.payload, mockResponse.data, mockRootSpan)
      .next({
        applicationId: action.payload.applicationId,
        toLoadPageId: pageId,
        toLoadBasePageId: basePageId,
      })
      .call(
        executeActionDuringUserDetailsInitialisation,
        ReduxActionTypes.END_CONSOLIDATED_PAGE_LOAD,
        action.payload.shouldInitialiseUserDetails,
      )
      .next()
      .call(engine.loadAppURL, {
        basePageId: action.payload.basePageId,
        basePageIdInUrl: action.payload.basePageId,
        rootSpan: mockRootSpan,
      })
      .next()
      .call(
        engine.loadAppEntities,
        pageId,
        action.payload.applicationId,
        mockResponse.data,
        mockRootSpan,
      )
      .next()
      .call(engine.loadGit, action.payload.applicationId, mockRootSpan)
      .next()
      .call(engine.completeChore, mockRootSpan)
      .next()
      .put(generateAutoHeightLayoutTreeAction(true, false))
      .next()
      .isDone();
  });

  it("sends a User from an editor URL to the same page in view mode", () => {
    const engine = { setupEngine: jest.fn(), loadAppData: jest.fn() };
    const mockRootSpan = startRootSpan("startAppEngine");
    const replace = jest
      .spyOn(history, "replace")
      .mockImplementation(jest.fn());

    window.history.pushState({}, "", "/app/my-app/page1-abc/edit?branch=main");
    (AppEngineFactory.create as jest.Mock).mockReturnValue(engine);

    testSaga(startAppEngine, action)
      .next()
      .call(engine.setupEngine, action.payload, mockRootSpan)
      .next()
      .call(getInitResponses, { ...action.payload })
      .next(mockResponse.data)
      .select(getCurrentUserSelector)
      .next({ ...DefaultCurrentUserDetails, role: "USER" })
      .call(
        executeActionDuringUserDetailsInitialisation,
        ReduxActionTypes.END_CONSOLIDATED_PAGE_LOAD,
        action.payload.shouldInitialiseUserDetails,
      )
      .next()
      .isDone();

    expect(replace).toHaveBeenCalledWith({
      pathname: "/app/my-app/page1-abc",
      search: "?branch=main",
    });
    expect(engine.loadAppData).not.toHaveBeenCalled();
    replace.mockRestore();
  });

  it("sends a Dev without Edit on the app to view mode with a clear message", () => {
    const engine = { setupEngine: jest.fn(), loadAppData: jest.fn() };
    const mockRootSpan = startRootSpan("startAppEngine");
    const replace = jest
      .spyOn(history, "replace")
      .mockImplementation(jest.fn());
    const show = jest.spyOn(toast, "show").mockImplementation(jest.fn());
    const refused = {
      ...mockResponse.data,
      pages: {
        responseMeta: {
          status: 404,
          success: false,
          error: { message: "Unable to find page basePageId" },
        },
      },
    };

    window.history.pushState({}, "", "/app/my-app/page1-abc/edit");
    (AppEngineFactory.create as jest.Mock).mockReturnValue(engine);

    testSaga(startAppEngine, action)
      .next()
      .call(engine.setupEngine, action.payload, mockRootSpan)
      .next()
      .call(getInitResponses, { ...action.payload })
      .next(refused)
      .select(getCurrentUserSelector)
      .next({ ...DefaultCurrentUserDetails, role: "DEV" })
      .call(
        executeActionDuringUserDetailsInitialisation,
        ReduxActionTypes.END_CONSOLIDATED_PAGE_LOAD,
        action.payload.shouldInitialiseUserDetails,
      )
      .next()
      .isDone();

    expect(show).toHaveBeenCalledWith(NO_EDIT_ACCESS_MESSAGE, {
      kind: "info",
    });
    expect(replace).toHaveBeenCalledWith({
      pathname: "/app/my-app/page1-abc",
      search: "",
    });
    expect(engine.loadAppData).not.toHaveBeenCalled();
    replace.mockRestore();
    show.mockRestore();
  });

  it("tells a refused edit-mode load from a normal one", () => {
    expect(isEditModeRefused(mockResponse.data as never)).toBe(false);
    expect(
      isEditModeRefused({
        pages: { responseMeta: { status: 404, success: false } },
      } as never),
    ).toBe(true);
  });
});

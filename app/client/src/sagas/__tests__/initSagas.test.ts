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
import { initAppViewerAction } from "actions/initActions";
import { NO_EDIT_ACCESS_MESSAGE } from "ee/utils/instanceRoles";
import { PageNotFoundError, type AppEnginePayload } from "entities/Engine";
import { expectSaga, testSaga } from "redux-saga-test-plan";
import * as matchers from "redux-saga-test-plan/matchers";
import { dynamic } from "redux-saga-test-plan/providers";
import { getFavoriteApplicationIds } from "ee/selectors/applicationSelectors";
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

  // The editor's init goes on as the viewer's: the viewer's own init is dropped while this one runs.
  const viewerAction = initAppViewerAction({
    ...action.payload,
    branch: "",
    mode: APP_MODE.PUBLISHED,
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
      .put(viewerAction)
      .next()
      .call(startAppEngine, viewerAction)
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
      .put(viewerAction)
      .next()
      .call(startAppEngine, viewerAction)
      .next(true)
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

  describe("runs the init once per mode, with no loop", () => {
    const refused = {
      ...mockResponse.data,
      pages: { responseMeta: { status: 404, success: false } },
    };
    let engines: Record<string, ReturnType<typeof makeEngine>>;
    let replace: jest.SpyInstance;
    let show: jest.SpyInstance;

    function makeEngine() {
      return {
        setupEngine: jest.fn(),
        loadAppData: jest.fn().mockReturnValue({
          applicationId: "applicationId",
          toLoadPageId: pageId,
          toLoadBasePageId: basePageId,
        }),
        loadAppURL: jest.fn(),
        loadAppEntities: jest.fn(),
        loadGit: jest.fn(),
        completeChore: jest.fn(),
      };
    }

    // Runs startAppEngine for real, recursion included; responses are given per mode.
    function run(
      role: string,
      responses: Partial<Record<APP_MODE, unknown>>,
    ): ReturnType<typeof expectSaga> {
      return expectSaga(startAppEngine, action).provide([
        [
          matchers.call.fn(getInitResponses),
          dynamic(({ args: [payload] }) => {
            const response = responses[payload.mode as APP_MODE];

            if (response instanceof Error) throw response;

            return response;
          }),
        ],
        [
          matchers.select(getCurrentUserSelector),
          { ...DefaultCurrentUserDetails, role },
        ],
        [matchers.select(getFavoriteApplicationIds), []],
      ]);
    }

    beforeEach(() => {
      engines = {
        [APP_MODE.EDIT]: makeEngine(),
        [APP_MODE.PUBLISHED]: makeEngine(),
      };
      (AppEngineFactory.create as jest.Mock)
        .mockReset()
        .mockImplementation((mode: APP_MODE) => engines[mode]);
      replace = jest.spyOn(history, "replace").mockImplementation(jest.fn());
      show = jest.spyOn(toast, "show").mockImplementation(jest.fn());
      window.history.pushState({}, "", "/app/my-app/page1-abc/edit");
    });

    afterEach(() => {
      replace.mockRestore();
      show.mockRestore();
    });

    it("gives a Dev with Edit one edit init and no viewer init", async () => {
      await run("DEV", { [APP_MODE.EDIT]: mockResponse.data })
        .not.put(viewerAction)
        .run();

      expect(AppEngineFactory.create).toHaveBeenCalledTimes(1);
      expect(AppEngineFactory.create).toHaveBeenCalledWith(
        APP_MODE.EDIT,
        APP_MODE.EDIT,
      );
      expect(engines[APP_MODE.EDIT].loadAppData).toHaveBeenCalledTimes(1);
      expect(replace).not.toHaveBeenCalled();
      expect(show).not.toHaveBeenCalled();
    });

    it("gives a User one viewer init after the editor's, and stops", async () => {
      await run("USER", {
        [APP_MODE.EDIT]: mockResponse.data,
        [APP_MODE.PUBLISHED]: mockResponse.data,
      })
        .put(viewerAction)
        .run();

      // One engine per init: the editor's, then the viewer's.
      expect(
        (AppEngineFactory.create as jest.Mock).mock.calls.map(([mode]) => mode),
      ).toEqual([APP_MODE.EDIT, APP_MODE.PUBLISHED]);
      expect(engines[APP_MODE.EDIT].loadAppData).not.toHaveBeenCalled();
      expect(engines[APP_MODE.PUBLISHED].loadAppData).toHaveBeenCalledTimes(1);
      expect(replace).toHaveBeenCalledTimes(1);
      expect(show).not.toHaveBeenCalled();
    });

    it("gives a Dev without Edit one viewer init and the message once", async () => {
      await run("DEV", {
        [APP_MODE.EDIT]: refused,
        [APP_MODE.PUBLISHED]: mockResponse.data,
      })
        .put(viewerAction)
        .run();

      expect(
        (AppEngineFactory.create as jest.Mock).mock.calls.map(([mode]) => mode),
      ).toEqual([APP_MODE.EDIT, APP_MODE.PUBLISHED]);
      expect(engines[APP_MODE.PUBLISHED].loadAppData).toHaveBeenCalledTimes(1);
      expect(show).toHaveBeenCalledTimes(1);
      expect(show).toHaveBeenCalledWith(NO_EDIT_ACCESS_MESSAGE, {
        kind: "info",
      });
    });

    it("leaves out the message when the app doesn't exist", async () => {
      await run("DEV", {
        [APP_MODE.EDIT]: refused,
        [APP_MODE.PUBLISHED]: new PageNotFoundError("Cannot find page"),
      })
        .put(viewerAction)
        .run();

      expect(AppEngineFactory.create).toHaveBeenCalledTimes(2);
      expect(engines[APP_MODE.PUBLISHED].loadAppData).not.toHaveBeenCalled();
      expect(show).not.toHaveBeenCalled();
    });
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

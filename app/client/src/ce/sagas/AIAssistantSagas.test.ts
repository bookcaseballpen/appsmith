import { expectSaga } from "redux-saga-test-plan";

import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { ENTITY_TYPE } from "ee/entities/DataTree/types";
import { IDE_TYPE } from "ee/IDE/Interfaces/IDETypes";
import { getIDETypeByUrl } from "ee/entities/IDE/utils";
import UserApi from "ee/api/UserApi";
import {
  getAIArtifact,
  getAIAssistantState,
  getAIEditorContext,
  getIsAIConfigLoaded,
} from "ee/selectors/aiAssistantSelectors";
import { getIsBuiltInAiEnabled } from "ee/selectors/builtInAiSelectors";
import OrganizationApi from "ee/api/OrganizationApi";
import { getCurrentUser } from "selectors/usersSelectors";
import aiAssistantSagasListener from "./AIAssistantSagas";

jest.mock("@appsmith/ads", () => ({
  ...jest.requireActual("@appsmith/ads"),
  toast: { show: jest.fn() },
}));

const APP_EDITOR_URL = "/app/my-app/page1-6123456789abcdef01234567/edit";

const aiState = {
  isEnabled: true,
  provider: "CLAUDE",
  messages: [],
};

const okResponse = {
  responseMeta: { success: true },
  data: { response: "done" },
};

const application = { artifactId: "app-1", artifactType: "APPLICATION" };

/**
 * Drives the real FETCH_AI_RESPONSE path the way the side panel does: the whole editor context is the request
 * context. Returns the context the saga sends to the server and the IDE type it passed to getAIArtifact.
 */
async function requestFor(url: string, editorContext: Record<string, unknown>) {
  window.history.pushState({}, "", url);

  const requestAIResponse = jest.fn().mockResolvedValue(okResponse);
  const artifactArgs: unknown[][] = [];

  await expectSaga(aiAssistantSagasListener)
    .provide({
      select({ args, selector }, next) {
        if (selector === getIsBuiltInAiEnabled) return true;

        if (selector === getAIAssistantState) return aiState;

        if (selector === getAIEditorContext) return editorContext;

        if (selector === getAIArtifact) {
          artifactArgs.push(args);

          return application;
        }

        return next();
      },
      call(effect, next) {
        return effect.fn === UserApi.requestAIResponse
          ? requestAIResponse(...effect.args)
          : next();
      },
    })
    .dispatch({
      type: ReduxActionTypes.FETCH_AI_RESPONSE,
      payload: { prompt: "Find and fix any bugs", context: editorContext },
    })
    .silentRun(1000);

  expect(requestAIResponse).toHaveBeenCalledTimes(1);

  return { context: requestAIResponse.mock.calls[0][2], artifactArgs };
}

describe("fetchAIResponseSaga request context", () => {
  afterEach(() => {
    window.history.pushState({}, "", "/");
  });

  // EE overrides getAIArtifact per editor. The IDE type comes from the URL, because the ids of earlier editors stay
  // in Redux after the user leaves them.
  it("asks getAIArtifact for the artifact of the editor in the URL", async () => {
    const { artifactArgs } = await requestFor(APP_EDITOR_URL, {
      mode: "javascript",
      entityName: "JSObject1",
      entityType: ENTITY_TYPE.JSACTION,
    });

    expect(artifactArgs).toEqual([[IDE_TYPE.App]]);
  });

  // The workflow editor URL resolves to IDE_TYPE.Workflow in EE and to IDE_TYPE.None in CE, which has no workflow
  // editor. Either way the request carries the URL's type, not the app editor's.
  it("passes the IDE type of a non-app URL, not the app editor's", async () => {
    const url = "/workflow/6123456789abcdef01234568/edit";
    const { artifactArgs } = await requestFor(url, {
      mode: "javascript",
      entityName: "Main",
      entityType: ENTITY_TYPE.JSACTION,
    });

    expect(artifactArgs).toEqual([[getIDETypeByUrl(url)]]);
    expect(artifactArgs[0][0]).not.toBe(IDE_TYPE.App);
  });

  it("sends the artifact from a JS object, which has no entity id", async () => {
    const { context } = await requestFor(APP_EDITOR_URL, {
      mode: "javascript",
      entityName: "JSObject1",
      entityType: ENTITY_TYPE.JSACTION,
    });

    expect(context).toMatchObject(application);
    expect(context).not.toHaveProperty("entityId");
  });

  it("does not send a widget's id as the entity id from a widget property binding", async () => {
    const { context } = await requestFor(APP_EDITOR_URL, {
      mode: "javascript",
      entityName: "Table1",
      entityId: "widget-1",
      entityType: ENTITY_TYPE.WIDGET,
    });

    expect(context).toMatchObject(application);
    expect(context).not.toHaveProperty("entityId");
  });

  it("sends a query's action id as the entity id", async () => {
    const { context } = await requestFor(APP_EDITOR_URL, {
      mode: "sql",
      entityName: "Query1",
      entityId: "action-1",
      entityType: ENTITY_TYPE.ACTION,
    });

    expect(context).toMatchObject({ entityId: "action-1", ...application });
  });
});

describe("built-in AI switch", () => {
  const signedInUser = { email: "dev@example.com", isAnonymous: false };

  async function loadSettingsWith(isBuiltInAiEnabled: boolean) {
    const getAIConfig = jest
      .fn()
      .mockResolvedValue({ data: { isAIAssistantEnabled: true } });

    const result = await expectSaga(aiAssistantSagasListener)
      .provide({
        select({ selector }, next) {
          if (selector === getIsBuiltInAiEnabled) return isBuiltInAiEnabled;

          if (selector === getIsAIConfigLoaded) return false;

          if (selector === getCurrentUser) return signedInUser;

          return next();
        },
        call(effect, next) {
          return effect.fn === OrganizationApi.getAIConfig
            ? getAIConfig()
            : next();
        },
      })
      .dispatch({ type: ReduxActionTypes.FETCH_FEATURE_FLAGS_SUCCESS })
      .silentRun(500);

    return { getAIConfig, result };
  }

  it("does not request the AI settings when the switch is off", async () => {
    const { getAIConfig, result } = await loadSettingsWith(false);

    expect(getAIConfig).not.toHaveBeenCalled();
    expect(
      result.effects.put?.some(
        (effect) =>
          effect.payload.action.type === ReduxActionTypes.UPDATE_AI_SETTINGS,
      ) ?? false,
    ).toBe(false);
  });

  it("requests the AI settings once the flags arrive with the switch on", async () => {
    const { getAIConfig } = await loadSettingsWith(true);

    expect(getAIConfig).toHaveBeenCalledTimes(1);
  });

  it("refuses a prompt at once when the switch is off", async () => {
    const requestAIResponse = jest.fn();

    await expectSaga(aiAssistantSagasListener)
      .provide({
        select({ selector }, next) {
          if (selector === getIsBuiltInAiEnabled) return false;

          if (selector === getAIAssistantState) return aiState;

          return next();
        },
        call(effect, next) {
          return effect.fn === UserApi.requestAIResponse
            ? requestAIResponse()
            : next();
        },
      })
      .put({
        type: ReduxActionTypes.FETCH_AI_RESPONSE_ERROR,
        payload: { error: "The built-in AI is disabled on this instance." },
      })
      .dispatch({
        type: ReduxActionTypes.FETCH_AI_RESPONSE,
        payload: { prompt: "Find and fix any bugs", context: {} },
      })
      .silentRun(500);

    expect(requestAIResponse).not.toHaveBeenCalled();
  });
});

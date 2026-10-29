import React from "react";
import "@testing-library/jest-dom";
import { act, render } from "@testing-library/react";
import { Provider } from "react-redux";
import type { Middleware } from "redux";
import { applyMiddleware, combineReducers, createStore } from "redux";
import { ThemeProvider } from "styled-components";

import CreateNewAppFromTemplatesModal from ".";
import { lightTheme } from "selectors/themeSelectors";
import {
  ReduxActionErrorTypes,
  ReduxActionTypes,
} from "ee/constants/ReduxActionConstants";
import templateReducer from "reducers/uiReducers/templateReducer";
import { shouldFetchTemplateFiltersSelector } from "selectors/templatesSelectors";

// The template service answers filters with an empty list when cloud services are off
const EMPTY_FILTERS = { functions: [], useCases: [] };

const createTestStore = (filtersResponseType: string) => {
  const requestedActions: string[] = [];
  const fakeTemplatesApi: Middleware = (api) => (next) => (action) => {
    requestedActions.push(action.type);

    const result = next(action);

    if (action.type === ReduxActionTypes.GET_TEMPLATE_FILTERS_INIT) {
      // Respond asynchronously, like the real request
      Promise.resolve().then(() =>
        api.dispatch({ type: filtersResponseType, payload: EMPTY_FILTERS }),
      );
    }

    return result;
  };

  const store = createStore(
    combineReducers({
      ui: combineReducers({ templates: templateReducer }),
      entities: () => ({ plugins: { defaultPluginList: [{ id: "plugin" }] } }),
    }),
    applyMiddleware(fakeTemplatesApi),
  );

  const filterRequestCount = () =>
    requestedActions.filter(
      (type) => type === ReduxActionTypes.GET_TEMPLATE_FILTERS_INIT,
    ).length;

  return { store, filterRequestCount };
};

const flushResponses = async () => {
  for (let i = 0; i < 20; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
};

describe("CreateNewAppFromTemplatesModal template filters", () => {
  it.each([
    ["an empty result", ReduxActionTypes.GET_TEMPLATE_FILTERS_SUCCESS],
    ["an error", ReduxActionErrorTypes.GET_TEMPLATE_FILTERS_ERROR],
  ])(
    "requests the filters once and does not refetch after %s",
    async (_label, responseType) => {
      const { filterRequestCount, store } = createTestStore(responseType);

      render(
        <Provider store={store}>
          <ThemeProvider theme={lightTheme}>
            <CreateNewAppFromTemplatesModal
              currentWorkSpaceId="workspaceId"
              handleClose={jest.fn()}
              isOpen={false}
            />
          </ThemeProvider>
        </Provider>,
      );

      await flushResponses();

      expect(filterRequestCount()).toBe(1);
    },
  );
});

describe("shouldFetchTemplateFiltersSelector", () => {
  const stateWith = (templates: ReturnType<typeof templateReducer>) =>
    ({ ui: { templates } }) as unknown as Parameters<
      typeof shouldFetchTemplateFiltersSelector
    >[0];

  it("is true before any request and false while one is in flight", () => {
    const initial = templateReducer(undefined, {
      type: "@@INIT",
      payload: undefined,
    });
    const loading = templateReducer(initial, {
      type: ReduxActionTypes.GET_TEMPLATE_FILTERS_INIT,
      payload: undefined,
    });

    expect(shouldFetchTemplateFiltersSelector(stateWith(initial))).toBe(true);
    expect(shouldFetchTemplateFiltersSelector(stateWith(loading))).toBe(false);
  });

  it("treats an empty result as final", () => {
    const initial = templateReducer(undefined, {
      type: "@@INIT",
      payload: undefined,
    });
    const fetched = templateReducer(initial, {
      type: ReduxActionTypes.GET_TEMPLATE_FILTERS_SUCCESS,
      payload: EMPTY_FILTERS,
    });

    expect(shouldFetchTemplateFiltersSelector(stateWith(fetched))).toBe(false);
  });
});

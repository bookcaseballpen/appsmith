import { testSaga } from "redux-saga-test-plan";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import { getCatalogQuery } from "ee/selectors/catalogSelectors";
import { DEFAULT_CATALOG_QUERY } from "ee/reducers/uiReducers/catalogReducer";
import {
  CATALOG_SEARCH_DEBOUNCE_MS,
  fetchCatalogSaga,
  fetchCatalogTagsSaga,
  refreshCatalogOnHomePageSaga,
  requestCatalog,
  requestCatalogTags,
} from "./catalogSagas";

jest.mock("@appsmith/ads", () => ({ toast: { show: jest.fn() } }));

const page = { items: [], total: 0, page: 0, size: 24 };

describe("catalogSagas", () => {
  it("waits for the typing to settle before searching", () => {
    testSaga(fetchCatalogSaga, {
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { search: "fin" },
    })
      .next()
      .delay(CATALOG_SEARCH_DEBOUNCE_MS)
      .next()
      .select(getCatalogQuery)
      .next({ ...DEFAULT_CATALOG_QUERY, search: "fin" })
      .call(requestCatalog, { ...DEFAULT_CATALOG_QUERY, search: "fin" })
      .next(page)
      .put({ type: ReduxActionTypes.FETCH_CATALOG_SUCCESS, payload: page })
      .next()
      .isDone();
  });

  it("fetches at once for a tag, sort or page change", () => {
    testSaga(fetchCatalogSaga, {
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { tags: ["finance"] },
    })
      .next()
      .select(getCatalogQuery)
      .next(DEFAULT_CATALOG_QUERY)
      .call(requestCatalog, DEFAULT_CATALOG_QUERY)
      .next(page)
      .put({ type: ReduxActionTypes.FETCH_CATALOG_SUCCESS, payload: page })
      .next()
      .isDone();
  });

  it("keeps the server's message when the fetch fails", () => {
    testSaga(fetchCatalogSaga, {
      type: ReduxActionTypes.FETCH_CATALOG_INIT,
      payload: {},
    })
      .next()
      .select(getCatalogQuery)
      .next(DEFAULT_CATALOG_QUERY)
      .call(requestCatalog, DEFAULT_CATALOG_QUERY)
      .throw(new Error("Invalid parameter: tags"))
      .put({
        type: ReduxActionTypes.FETCH_CATALOG_ERROR,
        payload: { message: "Invalid parameter: tags" },
      })
      .next()
      .isDone();
  });

  it("stores the tags for the filter chips", () => {
    testSaga(fetchCatalogTagsSaga)
      .next()
      .call(requestCatalogTags)
      .next(["finance", "hr"])
      .put({
        type: ReduxActionTypes.FETCH_CATALOG_TAGS_SUCCESS,
        payload: ["finance", "hr"],
      })
      .next()
      .isDone();
  });

  it("refreshes after an app change only on the home page", () => {
    window.history.pushState({}, "", "/app/my-app/page1-abc/edit");
    testSaga(refreshCatalogOnHomePageSaga).next().isDone();

    window.history.pushState({}, "", "/applications");
    testSaga(refreshCatalogOnHomePageSaga)
      .next()
      .call(fetchCatalogSaga, {
        type: ReduxActionTypes.FETCH_CATALOG_INIT,
        payload: {},
      })
      .next()
      .call(fetchCatalogTagsSaga)
      .next()
      .isDone();
  });
});

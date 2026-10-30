import { call, delay, put, select, takeLatest } from "redux-saga/effects";
import { toast } from "@appsmith/ads";
import log from "loglevel";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import type { ReduxAction } from "actions/ReduxActionTypes";
import CatalogApi, {
  type CatalogPage,
  type CatalogQuery,
} from "ee/api/CatalogApi";
import { getCatalogQuery } from "ee/selectors/catalogSelectors";
import { callApprovalApi } from "ee/utils/approvals";
import { APPLICATIONS_URL } from "constants/routes";

// Typing in the search box waits this long before asking the server.
export const CATALOG_SEARCH_DEBOUNCE_MS = 300;

export const requestCatalog = async (query: CatalogQuery) =>
  callApprovalApi<CatalogPage>(
    async () => CatalogApi.fetchCatalog(query),
    "Could not load the apps",
  );

export const requestCatalogTags = async () =>
  callApprovalApi<string[]>(
    async () => CatalogApi.fetchTags(),
    "Could not load the tags",
  );

/**
 * Fork: loads one page of the home page catalog with the current query. A change of the search text waits for the
 * typing to settle first; takeLatest drops a request a newer change overtook.
 */
export function* fetchCatalogSaga(action: ReduxAction<Partial<CatalogQuery>>) {
  if (
    action.type === ReduxActionTypes.SET_CATALOG_QUERY &&
    action.payload?.search !== undefined
  ) {
    yield delay(CATALOG_SEARCH_DEBOUNCE_MS);
  }

  const query: CatalogQuery = yield select(getCatalogQuery);

  try {
    const page: CatalogPage = yield call(requestCatalog, query);

    yield put({ type: ReduxActionTypes.FETCH_CATALOG_SUCCESS, payload: page });
  } catch (error) {
    const message = (error as Error).message;

    yield put({
      type: ReduxActionTypes.FETCH_CATALOG_ERROR,
      payload: { message },
    });
    toast.show(message, { kind: "error" });
  }
}

export function* fetchCatalogTagsSaga() {
  try {
    const tags: string[] = yield call(requestCatalogTags);

    yield put({
      type: ReduxActionTypes.FETCH_CATALOG_TAGS_SUCCESS,
      payload: tags,
    });
  } catch (error) {
    log.error(error);
  }
}

/** An app was renamed, recoloured or deleted from its card: the home page shows the catalog again. */
export function* refreshCatalogOnHomePageSaga() {
  if (!window.location.pathname.startsWith(APPLICATIONS_URL)) return;

  yield call(fetchCatalogSaga, {
    type: ReduxActionTypes.FETCH_CATALOG_INIT,
    payload: {},
  });
  yield call(fetchCatalogTagsSaga);
}

export default function* catalogSagas() {
  yield takeLatest(
    [ReduxActionTypes.SET_CATALOG_QUERY, ReduxActionTypes.FETCH_CATALOG_INIT],
    fetchCatalogSaga,
  );
  yield takeLatest(
    ReduxActionTypes.FETCH_CATALOG_TAGS_INIT,
    fetchCatalogTagsSaga,
  );
  yield takeLatest(
    [
      ReduxActionTypes.DELETE_APPLICATION_SUCCESS,
      ReduxActionTypes.UPDATE_APPLICATION_SUCCESS,
    ],
    refreshCatalogOnHomePageSaga,
  );
}

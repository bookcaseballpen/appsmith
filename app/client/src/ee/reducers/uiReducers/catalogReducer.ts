import { createImmerReducer } from "utils/ReducerUtils";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import type { ReduxAction } from "actions/ReduxActionTypes";
import type { CatalogItem, CatalogPage, CatalogQuery } from "ee/api/CatalogApi";

export const CATALOG_PAGE_SIZE = 24;

export const DEFAULT_CATALOG_QUERY: CatalogQuery = {
  search: "",
  tags: [],
  tagMode: "any",
  sort: "name",
  page: 0,
  size: CATALOG_PAGE_SIZE,
};

/** Fork: the home page catalog. The server filters, sorts and pages; this keeps the query and the current page. */
export interface CatalogReduxState {
  query: CatalogQuery;
  items: CatalogItem[];
  total: number;
  isFetching: boolean;
  // Set while the last fetch failed; the previous page stays shown.
  error: string | null;
  tags: string[];
}

export const initialCatalogState: CatalogReduxState = {
  query: DEFAULT_CATALOG_QUERY,
  items: [],
  total: 0,
  isFetching: false,
  error: null,
  tags: [],
};

const catalogReducer = createImmerReducer(initialCatalogState, {
  [ReduxActionTypes.SET_CATALOG_QUERY]: (
    state: CatalogReduxState,
    action: ReduxAction<Partial<CatalogQuery>>,
  ) => {
    // Any other change starts again at the first page.
    state.query = { ...state.query, page: 0, ...action.payload };
    state.isFetching = true;
  },
  [ReduxActionTypes.FETCH_CATALOG_INIT]: (state: CatalogReduxState) => {
    state.isFetching = true;
  },
  [ReduxActionTypes.FETCH_CATALOG_SUCCESS]: (
    state: CatalogReduxState,
    action: ReduxAction<CatalogPage>,
  ) => {
    state.items = action.payload.items;
    state.total = action.payload.total;
    state.isFetching = false;
    state.error = null;
  },
  [ReduxActionTypes.FETCH_CATALOG_ERROR]: (
    state: CatalogReduxState,
    action: ReduxAction<{ message: string }>,
  ) => {
    state.isFetching = false;
    state.error = action.payload.message;
  },
  [ReduxActionTypes.FETCH_CATALOG_TAGS_SUCCESS]: (
    state: CatalogReduxState,
    action: ReduxAction<string[]>,
  ) => {
    state.tags = action.payload;
  },
});

export default catalogReducer;

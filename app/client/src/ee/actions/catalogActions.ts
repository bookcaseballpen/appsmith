import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import type { CatalogQuery } from "ee/api/CatalogApi";

/** Fork: changes the home page catalog's query (any change but the page goes back to the first page) and refetches. */
export const setCatalogQuery = (query: Partial<CatalogQuery>) => ({
  type: ReduxActionTypes.SET_CATALOG_QUERY,
  payload: query,
});

export const fetchCatalog = () => ({
  type: ReduxActionTypes.FETCH_CATALOG_INIT,
});

export const fetchCatalogTags = () => ({
  type: ReduxActionTypes.FETCH_CATALOG_TAGS_INIT,
});

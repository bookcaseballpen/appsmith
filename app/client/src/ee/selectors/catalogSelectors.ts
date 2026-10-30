import type { DefaultRootState } from "react-redux";
import {
  initialCatalogState,
  type CatalogReduxState,
} from "ee/reducers/uiReducers/catalogReducer";

// Fork: the home page catalog.
const getCatalogState = (state: DefaultRootState): CatalogReduxState =>
  state.ui.catalog ?? initialCatalogState;

export const getCatalogQuery = (state: DefaultRootState) =>
  getCatalogState(state).query;

export const getCatalogItems = (state: DefaultRootState) =>
  getCatalogState(state).items;

export const getCatalogTotal = (state: DefaultRootState) =>
  getCatalogState(state).total;

export const getIsFetchingCatalog = (state: DefaultRootState) =>
  getCatalogState(state).isFetching;

export const getCatalogError = (state: DefaultRootState) =>
  getCatalogState(state).error;

export const getCatalogTags = (state: DefaultRootState) =>
  getCatalogState(state).tags;

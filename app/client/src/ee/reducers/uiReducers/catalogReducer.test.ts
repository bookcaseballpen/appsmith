import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import catalogReducer, {
  DEFAULT_CATALOG_QUERY,
  initialCatalogState,
} from "./catalogReducer";

describe("catalogReducer", () => {
  it("goes back to the first page when the filter changes", () => {
    const onPage3 = {
      ...initialCatalogState,
      query: { ...DEFAULT_CATALOG_QUERY, page: 2 },
    };

    const state = catalogReducer(onPage3, {
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { tags: ["finance"] },
    });

    expect(state.query).toEqual({
      ...DEFAULT_CATALOG_QUERY,
      tags: ["finance"],
      page: 0,
    });
    expect(state.isFetching).toBe(true);
  });

  it("keeps the page asked for", () => {
    const state = catalogReducer(initialCatalogState, {
      type: ReduxActionTypes.SET_CATALOG_QUERY,
      payload: { page: 3 },
    });

    expect(state.query.page).toBe(3);
  });

  it("stores a page and clears the error", () => {
    const failed = catalogReducer(initialCatalogState, {
      type: ReduxActionTypes.FETCH_CATALOG_ERROR,
      payload: { message: "boom" },
    });
    const items = [{ id: "a", name: "A" }];

    const state = catalogReducer(failed, {
      type: ReduxActionTypes.FETCH_CATALOG_SUCCESS,
      payload: { items, total: 30, page: 0, size: 24 },
    });

    expect(failed.error).toBe("boom");
    expect(state.items).toEqual(items);
    expect(state.total).toBe(30);
    expect(state.error).toBeNull();
    expect(state.isFetching).toBe(false);
  });
});

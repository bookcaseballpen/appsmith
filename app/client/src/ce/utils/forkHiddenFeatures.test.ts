import {
  areAppsmithLinksHidden,
  isAppsmithLink,
  withoutAppsmithLink,
} from "ee/utils/forkHiddenFeatures";
import pluginsReducer from "reducers/entityReducers/pluginsReducer";
import { ReduxActionTypes } from "ee/constants/ReduxActionConstants";
import type { Plugin } from "entities/Plugin";

// Fork: no link to an Appsmith host is shown. See FORK.md, "Branding".
describe("Appsmith links", () => {
  it("are hidden", () => {
    expect(areAppsmithLinksHidden()).toBe(true);
  });

  it.each([
    "https://docs.appsmith.com/reference/widgets/button",
    "https://appsmith.com",
    "https://www.appsmith.com/blog/deprecating-fusion-charts",
    "https://community.appsmith.com/",
    "http://docs.appsmithai.com/x",
    "https://discord.gg/rBTTVJp",
    "https://discord.com/invite/abc",
    "https://github.com/appsmithorg/appsmith/issues/19037",
    "mailto: support@appsmith.com?subject=Appsmith 503 Server Error",
  ])("recognises %s", (url) => {
    expect(isAppsmithLink(url)).toBe(true);
    expect(withoutAppsmithLink(url)).toBeUndefined();
  });

  it.each([
    "https://www.jsdelivr.com/",
    "https://example.com/appsmith.com",
    "https://notappsmith.com.example.org/",
    "https://github.com/someone/appsmith",
    "/supervisor/logtail/backend",
    "",
  ])("keeps %p", (url) => {
    expect(isAppsmithLink(url)).toBe(false);
    expect(withoutAppsmithLink(url)).toBe(url);
  });

  it("keeps undefined and null", () => {
    expect(withoutAppsmithLink(undefined)).toBeUndefined();
    expect(withoutAppsmithLink(null)).toBeNull();
  });

  it("drops plugin documentation links to Appsmith's docs", () => {
    const state = pluginsReducer(undefined, {
      type: ReduxActionTypes.FETCH_PLUGINS_SUCCESS,
      payload: [
        { id: "1", documentationLink: "https://docs.appsmith.com/postgres" },
        { id: "2", documentationLink: "https://www.postgresql.org/docs/" },
        { id: "3" },
      ] as unknown as Plugin[],
    });

    expect(state.list.map((p: Plugin) => p.documentationLink)).toEqual([
      undefined,
      "https://www.postgresql.org/docs/",
      undefined,
    ]);
  });
});

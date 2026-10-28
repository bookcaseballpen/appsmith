import { getAssetUrl } from "ee/utils/airgapHelpers";
import { ASSETS_CDN_URL } from "constants/ThirdPartyConstants";

describe("getAssetUrl (fork)", () => {
  it.each([
    [
      "https://assets.appsmith.com/logo/postgresql.svg",
      "/assets-cdn/logo/postgresql.svg",
    ],
    ["http://assets.appsmith.com/RestAPI.png", "/assets-cdn/RestAPI.png"],
    [
      `${ASSETS_CDN_URL}/widgets/default.png`,
      "/assets-cdn/widgets/default.png",
    ],
    ["/api/v1/assets/123", "/api/v1/assets/123"],
    ["https://example.com/logo.png", "https://example.com/logo.png"],
    [
      "https://assets.appsmith.com.example.com/x.png",
      "https://assets.appsmith.com.example.com/x.png",
    ],
    ["", ""],
  ])("maps %s to %s", (input, expected) => {
    expect(getAssetUrl(input)).toBe(expected);
  });
});

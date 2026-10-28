import {
  CUSTOMER_PORTAL_URL_WITH_PARAMS,
  PRICING_PAGE_URL,
} from "./ThirdPartyConstants";

describe.each([
  ["PRICING_PAGE_URL", PRICING_PAGE_URL],
  ["CUSTOMER_PORTAL_URL_WITH_PARAMS", CUSTOMER_PORTAL_URL_WITH_PARAMS],
])("%s", (_name, buildUrl) => {
  // Fork: there is no default URL, so an unset one must not become a relative link on this host.
  it("returns an empty string when the URL is not configured", () => {
    expect(buildUrl("", "CE", "instance-id", "feature", "section")).toBe("");
  });

  it("adds the query parameters to a configured URL", () => {
    expect(
      buildUrl("https://portal.example.com", "CE", "instance-id", "feature"),
    ).toBe(
      "https://portal.example.com?source=CE&instance=instance-id&feature=feature",
    );
  });
});

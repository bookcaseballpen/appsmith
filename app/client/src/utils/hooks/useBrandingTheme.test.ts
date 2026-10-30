import { setFavicon } from "./useBrandingTheme";
import { BRAND_FAVICON_URL } from "constants/Brand";

const CUSTOM_FAVICON = "https://example.com/custom.ico";

// The icon links of public/index.html.
const addIndexHtmlLinks = () => {
  document.head.innerHTML = `
    <link rel="icon" href="/brand/favicon.ico" />
    <link rel="icon" href="/brand/favicon.ico" media="(prefers-color-scheme: light)" />
    <link rel="icon" href="/brand/favicon-darkmode.ico" media="(prefers-color-scheme: dark)" />
    <link rel="apple-touch-icon" href="/brand/apple-touch-icon.png" />
  `;
};

const iconHrefs = () =>
  Array.from(document.querySelectorAll("link[rel~='icon']")).map((link) =>
    link.getAttribute("href"),
  );

describe("setFavicon", () => {
  beforeEach(() => {
    document.head.innerHTML = "";
  });

  it("points every icon link at a custom favicon, light and dark", () => {
    addIndexHtmlLinks();

    setFavicon(CUSTOM_FAVICON);

    expect(iconHrefs()).toEqual([
      CUSTOM_FAVICON,
      CUSTOM_FAVICON,
      CUSTOM_FAVICON,
    ]);
    expect(
      document
        .querySelector("link[rel='apple-touch-icon']")
        ?.getAttribute("href"),
    ).toBe("/brand/apple-touch-icon.png");
  });

  it("keeps the brand favicons when no custom one is set", () => {
    addIndexHtmlLinks();

    setFavicon(BRAND_FAVICON_URL);
    setFavicon(undefined);

    expect(iconHrefs()).toEqual([
      "/brand/favicon.ico",
      "/brand/favicon.ico",
      "/brand/favicon-darkmode.ico",
    ]);
  });

  it("puts the brand favicons back when the custom one is removed", () => {
    addIndexHtmlLinks();

    setFavicon(CUSTOM_FAVICON);
    setFavicon(BRAND_FAVICON_URL);

    expect(iconHrefs()).toEqual([
      "/brand/favicon.ico",
      "/brand/favicon.ico",
      "/brand/favicon-darkmode.ico",
    ]);
  });

  it("adds a link for a custom favicon when the page has none, and removes it again", () => {
    setFavicon(CUSTOM_FAVICON);

    expect(iconHrefs()).toEqual([CUSTOM_FAVICON]);

    setFavicon(BRAND_FAVICON_URL);

    expect(iconHrefs()).toEqual([]);
  });
});

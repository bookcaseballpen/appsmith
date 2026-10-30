/**
 * Fork: the product's name and brand assets. Every brand file lives in public/brand (served at /brand); swap the
 * logo by replacing those files, keeping their names. See FORK.md, "Branding".
 */
export const PRODUCT_NAME = "CreativeAppBuilder";

/** The "CREATIVE" wordmark carries no product name, so it is shown with this text beside or under it. */
export const PRODUCT_NAME_SUFFIX = "App Builder";

export const BRAND_ASSETS_URL = "/brand";

/** Colour wordmark (teal letters, orange A), for light backgrounds. */
export const BRAND_LOGO_URL = `${BRAND_ASSETS_URL}/creative-logo-colour.svg`;

/** White wordmark, for dark backgrounds. */
export const BRAND_LOGO_ON_DARK_URL = `${BRAND_ASSETS_URL}/creative-logo-white.svg`;

/** Square icon (the dark grey "A" mark), for light backgrounds; used where upstream shows its square logo. */
export const BRAND_ICON_URL = `${BRAND_ASSETS_URL}/icon.svg`;

/** Square icon (the light grey "A" mark), for dark backgrounds. */
export const BRAND_ICON_ON_DARK_URL = `${BRAND_ASSETS_URL}/icon-darkmode.svg`;

/** Favicon for a light browser theme; index.html switches to favicon-darkmode.ico for a dark one. */
export const BRAND_FAVICON_URL = `${BRAND_ASSETS_URL}/favicon.ico`;

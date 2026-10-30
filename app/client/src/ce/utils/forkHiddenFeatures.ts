/**
 * Fork: product areas this fork hides for good. Their code stays, but no screen, menu item or route leads to them,
 * and the server refuses their endpoints (HiddenEndpointsWebFilter). Built-in Git has its own switch, see
 * ce/selectors/builtInGitSelectors.ts. See FORK.md, "Hidden features".
 */

/**
 * One hidden workspace ("Apps"): no workspace list or switcher, no create, rename, delete, settings, members or
 * invite. The home page lists the apps of the default workspace; admins manage users and roles in Admin settings.
 */
export const isWorkspaceUiHidden = () => true;

/**
 * No template gallery, "start from template", "fork template", "add page from template" or building blocks, and no
 * request to /api/v1/app-templates.
 */
export const areTemplatesHidden = () => true;

/**
 * No signup profiling form, first-app ("connect your data") screen, onboarding checklist or signposting. The first
 * admin signup (/setup/welcome) stays.
 */
export const isOnboardingHidden = () => true;

/**
 * No link to an Appsmith host (docs, community, Discord, support, the blog) and no "Built on Appsmith" badge in the
 * app viewer. The product is CreativeAppBuilder; see FORK.md, "Branding".
 */
export const areAppsmithLinksHidden = () => true;

const APPSMITH_LINK =
  /^(?:(?:https?:)?\/\/(?:[a-z0-9-]+\.)*(?:appsmith\.com|appsmithai\.com|discord\.gg|discord\.com)(?=[/:?#]|$)|(?:https?:)?\/\/(?:www\.)?github\.com\/appsmithorg(?=[/?#]|$)|mailto:\s*[^?]*@appsmith\.com)/i;

/** Whether the URL points at an Appsmith host (docs, community, Discord, Appsmith's GitHub, Appsmith support mail). */
export const isAppsmithLink = (url?: string | null): boolean =>
  typeof url === "string" && APPSMITH_LINK.test(url.trim());

/** The URL, or undefined when it points at an Appsmith host and those links are hidden. */
export const withoutAppsmithLink = <T extends string | null | undefined>(
  url: T,
): T | undefined =>
  areAppsmithLinksHidden() && isAppsmithLink(url) ? undefined : url;

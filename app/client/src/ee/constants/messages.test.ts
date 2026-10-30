import * as messages from "ee/constants/messages";
import { createMessage } from "ee/constants/messages";
import { isApprovedErrorMessage } from "ee/constants/approvedErrorMessages";
import {
  getPageTitle,
  getHTMLPageTitle,
} from "ee/utils/BusinessFeatures/brandingPageHelpers";
import { getHideWatermark } from "ee/selectors/organizationSelectors";
import type { DefaultRootState } from "react-redux";

// Fork: users see the product as CreativeAppBuilder, with no link to an Appsmith host. See FORK.md, "Branding".
describe("CreativeAppBuilder branding", () => {
  it.each([
    [
      "APPSMITH_DISPLAY_VERSION",
      messages.APPSMITH_DISPLAY_VERSION("Community", "v1.0"),
    ],
    [
      "FORGOT_PASSWORD_SUCCESS_TEXT",
      messages.FORGOT_PASSWORD_SUCCESS_TEXT("a@example.com"),
    ],
    ["ERROR_403", messages.ERROR_403("page", "a@example.com")],
    [
      "MAKE_APPLICATION_PUBLIC_TOOLTIP",
      createMessage(messages.MAKE_APPLICATION_PUBLIC_TOOLTIP),
    ],
    [
      "SERVER_API_TIMEOUT_ERROR",
      createMessage(messages.SERVER_API_TIMEOUT_ERROR),
    ],
    [
      "PAGE_SERVER_TIMEOUT_TITLE",
      createMessage(messages.PAGE_SERVER_TIMEOUT_TITLE),
    ],
    [
      "PAGE_SERVER_UNAVAILABLE_TITLE",
      messages.PAGE_SERVER_UNAVAILABLE_TITLE(false),
    ],
    [
      "INFO_VERSION_MISMATCH_FOUND_RELOAD_REQUEST",
      createMessage(messages.INFO_VERSION_MISMATCH_FOUND_RELOAD_REQUEST),
    ],
    ["FORM_LOGIN_DESC", createMessage(messages.FORM_LOGIN_DESC)],
    [
      "STATIC_URL_DISABLED_SUCCESS",
      createMessage(messages.STATIC_URL_DISABLED_SUCCESS),
    ],
    [
      "IN_APP_EMBED_SETTING",
      messages.IN_APP_EMBED_SETTING.secondaryHeadingForAppSettings(),
    ],
  ])("%s names the product CreativeAppBuilder", (_name, text) => {
    expect(text).toContain("CreativeAppBuilder");
    expect(text).not.toContain("Appsmith");
  });

  it("shows the version without Appsmith's edition name", () => {
    expect(messages.APPSMITH_DISPLAY_VERSION("Community", "v1.2.3")).toBe(
      "CreativeAppBuilder v1.2.3",
    );
  });

  it("does not send users to Appsmith support or docs when the server is down", () => {
    for (const cloudHosting of [true, false]) {
      const hrefs = messages
        .PAGE_SERVER_UNAVAILABLE_ERROR_MESSAGES(cloudHosting)
        .flatMap((m) => m.links ?? [])
        .map((l) => l.href);

      expect(hrefs).toEqual(["/supervisor/logtail/backend"]);
    }

    expect(createMessage(messages.PAGE_CLIENT_ERROR_DESCRIPTION)).not.toMatch(
      /appsmith/i,
    );
    expect(createMessage(messages.DEBUGGER_APPSMITH_SUPPORT)).toBe(
      "Get support",
    );
  });

  it("does not name Appsmith on the upgrade pages", () => {
    for (const message of [
      messages.AVAILABLE_ON_ENTERPRISE,
      messages.USER_PROVISIONING_FOR_ENTERPRISES,
      messages.PROVISIONING_UPGRADE_PAGE_SUB_HEADING,
      messages.PROVISION_DEPROVISION_USERS_DETAIL1,
      messages.AUTO_GROUP_SYNC_DETAIL1,
      messages.PROVISIONING_UPGRADE_PAGE_FOOTER,
    ]) {
      expect(createMessage(message)).not.toContain("Appsmith");
    }
  });

  it("keeps the custom widget console free of Appsmith docs links", () => {
    expect(
      messages.CUSTOM_WIDGET_FEATURE.debugger.noOnReadyWarning(
        "https://docs.appsmith.com/x",
      ),
    ).not.toContain("appsmith.com");
    expect(messages.CUSTOM_WIDGET_FEATURE.debugger.title()).toBe("Console");
  });

  it("keeps the other embed texts", () => {
    expect(messages.IN_APP_EMBED_SETTING.embed()).toBe("Embed");
  });

  it("titles browser tabs with the product name", () => {
    expect(getHTMLPageTitle(false, "Anything")).toBe("CreativeAppBuilder");
    expect(getPageTitle(false, "Applications", undefined)).toBe(
      "Applications | CreativeAppBuilder",
    );
    expect(getPageTitle(false, undefined, undefined)).toBe(
      "CreativeAppBuilder",
    );
  });

  it("never shows the Built on Appsmith badge", () => {
    const state = {
      organization: { organizationConfiguration: { hideWatermark: false } },
    } as unknown as DefaultRootState;

    expect(getHideWatermark(state)).toBe(true);
  });

  it.each([
    "Please use Google authentication to login to CreativeAppBuilder",
    "Please use Google authentication to login to Appsmith",
    "A password reset link has been sent to your email address a@example.com registered with CreativeAppBuilder.",
    "Signup is restricted on this instance of CreativeAppBuilder. Please contact the administrator to get an invite for user a@example.com.",
  ])("shows the server message %p", (message) => {
    expect(isApprovedErrorMessage(message)).toBe(true);
  });
});

import {
  getSafeErrorMessage,
  isApprovedErrorMessage,
} from "ee/constants/approvedErrorMessages";
import { createMessage, DEFAULT_ERROR_MESSAGE } from "ee/constants/messages";

// The server's INVALID_LOGIN_METHOD message (AppsmithError): "Please use {0} authentication to login to " + the
// product name, where {0} is the user's login source (LoginSource), capitalized as the server does
// (CustomFormLoginServiceCEImpl).
const loginMethodMessage = (source: string, product = "CreativeAppBuilder") =>
  `Please use ${source} authentication to login to ${product}`;

describe("the authentication pattern of approved error messages", () => {
  it.each(["Google", "Github", "Keycloak", "Oidc"])(
    "shows the server's message for a %s account as it is",
    (source) => {
      const message = loginMethodMessage(source);

      expect(isApprovedErrorMessage(message)).toBe(true);
      expect(getSafeErrorMessage(message)).toBe(message);
    },
  );

  // Fork: an older server still names Appsmith (FORK.md, "Branding").
  it("still accepts the upstream product name", () => {
    expect(
      isApprovedErrorMessage(loginMethodMessage("Google", "Appsmith")),
    ).toBe(true);
  });

  it.each([
    loginMethodMessage("Google", "Another product"),
    `${loginMethodMessage("Google")}. Click <a href="https://example.com">here</a>`,
    `Hello. ${loginMethodMessage("Google")}`,
    loginMethodMessage("<script>alert(1)</script>"),
  ])("replaces anything else with the generic message: %p", (message) => {
    expect(isApprovedErrorMessage(message)).toBe(false);
    expect(getSafeErrorMessage(message)).toBe(
      createMessage(DEFAULT_ERROR_MESSAGE),
    );
  });
});

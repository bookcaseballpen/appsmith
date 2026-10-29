import type { User } from "constants/userConstants";
import { DefaultCurrentUserDetails } from "constants/userConstants";
import {
  canPublishRole,
  getInstanceRole,
  getViewerPathForBuilderPath,
  isReviewOnlyRole,
  isViewOnlyRole,
} from "./instanceRoles";

const userWithRole = (role?: string): User => ({
  ...DefaultCurrentUserDetails,
  role,
});

describe("instanceRoles", () => {
  it("reads a known role from the user", () => {
    expect(getInstanceRole(userWithRole("APPROVER"))).toBe("APPROVER");
  });

  it("ignores a missing or unknown role", () => {
    expect(getInstanceRole(userWithRole())).toBeUndefined();
    expect(getInstanceRole(userWithRole("engineer"))).toBeUndefined();
    expect(getInstanceRole(undefined)).toBeUndefined();
  });

  it("treats only USER as view-only and only APPROVER as review-only", () => {
    expect(isViewOnlyRole("USER")).toBe(true);
    expect(isViewOnlyRole("DEV")).toBe(false);
    expect(isViewOnlyRole(undefined)).toBe(false);
    expect(isReviewOnlyRole("APPROVER")).toBe(true);
    expect(isReviewOnlyRole("ADMIN")).toBe(false);
  });

  it.each([
    ["ADMIN", true],
    ["APPROVER", true],
    ["DEV", false],
    ["USER", false],
    [undefined, true],
  ] as const)("lets the role %s publish: %s", (role, allowed) => {
    expect(canPublishRole(role)).toBe(allowed);
  });

  it.each([
    ["/app/my-app/page1-abc123/edit", "/app/my-app/page1-abc123"],
    ["/app/my-app/page1-abc123/edit/queries/q1", "/app/my-app/page1-abc123"],
    ["/applications/app1/pages/page1/edit", "/applications/app1/pages/page1"],
    ["/app/static-app/static-page/edit/", "/app/static-app/static-page"],
  ])("maps the builder path %s to the viewer path %s", (builder, viewer) => {
    expect(getViewerPathForBuilderPath(builder)).toBe(viewer);
  });

  it("does not cut a page slug that merely starts with 'edit'", () => {
    expect(
      getViewerPathForBuilderPath("/app/my-app/editor-page-abc123/edit"),
    ).toBe("/app/my-app/editor-page-abc123");
  });
});

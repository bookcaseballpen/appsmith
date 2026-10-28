import type { DefaultRootState } from "react-redux";
import type { User } from "constants/userConstants";

/**
 * The four fixed instance-wide roles. The server derives them and sends `role` on /users/me; it also enforces
 * them. The client only hides what a role may not do.
 */
export const INSTANCE_ROLES = ["ADMIN", "DEV", "APPROVER", "USER"] as const;

export type InstanceRole = (typeof INSTANCE_ROLES)[number];

export const INSTANCE_ROLE_LABELS: Record<InstanceRole, string> = {
  ADMIN: "Admin",
  DEV: "Developer",
  APPROVER: "Approver",
  USER: "User",
};

export const getInstanceRole = (user?: User): InstanceRole | undefined => {
  const role = user?.role;

  return INSTANCE_ROLES.find((knownRole) => knownRole === role);
};

/** Users run published apps only: no edit mode, no preview. */
export const isViewOnlyRole = (role?: InstanceRole) => role === "USER";

/** Approvers open the editor read-only, to review an app before publishing it. */
export const isReviewOnlyRole = (role?: InstanceRole) => role === "APPROVER";

export const selectIsReviewOnlyUser = (state: DefaultRootState) =>
  isReviewOnlyRole(getInstanceRole(state.ui?.users?.currentUser));

/**
 * The view-mode URL for an editor URL: every builder route is its viewer route followed by "/edit" and an optional
 * editor sub-path (for example "/app/my-app/page1-abc/edit/queries/q1" -> "/app/my-app/page1-abc").
 */
export const getViewerPathForBuilderPath = (builderPath: string) => {
  const editIndex = builderPath.search(/\/edit(\/|$)/);

  return editIndex === -1 ? builderPath : builderPath.slice(0, editIndex);
};

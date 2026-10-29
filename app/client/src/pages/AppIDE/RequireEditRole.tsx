import React from "react";
import type { RouteComponentProps } from "react-router";
import { Redirect } from "react-router-dom";
import { useSelector } from "react-redux";

import { getCurrentUser } from "selectors/usersSelectors";
import {
  getInstanceRole,
  getViewerPathForBuilderPath,
  isViewOnlyRole,
} from "ee/utils/instanceRoles";
import AppIDELoader from "./AppIDELoader";

/**
 * Guards the editor routes by role. Users only run published apps, so an editor URL sends them to the same page in
 * view mode. Approvers get the editor, which stays in preview for them (see selectCombinedPreviewMode). This is UX
 * only: the server refuses edits from both roles.
 */
function RequireEditRole(props: RouteComponentProps) {
  const role = getInstanceRole(useSelector(getCurrentUser));

  if (isViewOnlyRole(role)) {
    return (
      <Redirect
        to={{
          pathname: getViewerPathForBuilderPath(props.location.pathname),
          search: props.location.search,
        }}
      />
    );
  }

  return <AppIDELoader {...props} />;
}

export default RequireEditRole;

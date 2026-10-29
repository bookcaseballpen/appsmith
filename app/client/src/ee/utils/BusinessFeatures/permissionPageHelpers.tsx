export * from "ce/utils/BusinessFeatures/permissionPageHelpers";
import {
  getHasCreateActionPermission as getHasCreateActionPermission_CE,
  getHasCreateDatasourceActionPermission,
  getHasCreatePagePermission as getHasCreatePagePermission_CE,
  getHasDeleteActionPermission as getHasDeleteActionPermission_CE,
  getHasDeletePagePermission as getHasDeletePagePermission_CE,
  getHasManageActionPermission as getHasManageActionPermission_CE,
  getHasManagePagePermission as getHasManagePagePermission_CE,
} from "ce/utils/BusinessFeatures/permissionPageHelpers";
import { IDE_TYPE, type IDEType } from "ee/IDE/Interfaces/IDETypes";
import { isEditorReadOnly } from "ee/utils/editorReadOnly";
import { isPermitted, PERMISSION_TYPE } from "ee/utils/permissionHelpers";

/**
 * Fork: CE allows every datasource action whatever the server sends, so a Dev saw Edit and New datasource buttons the
 * server then refused. These read the permissions the server sends instead. An empty list (a datasource not saved
 * yet) still allows, as CE does; the server enforces either way.
 */
const isAllowedBy = (permissions: string[] | undefined, type: string) =>
  !permissions?.length || isPermitted(permissions, type);

export const getHasCreateDatasourcePermission = (
  _isEnabled: boolean,
  workspacePermissions?: string[],
) => isAllowedBy(workspacePermissions, PERMISSION_TYPE.CREATE_DATASOURCES);

export const getHasManageDatasourcePermission = (
  _isEnabled: boolean,
  permissions?: string[],
) => isAllowedBy(permissions, PERMISSION_TYPE.MANAGE_DATASOURCES);

export const getHasDeleteDatasourcePermission = (
  _isEnabled: boolean,
  permissions?: string[],
) => isAllowedBy(permissions, PERMISSION_TYPE.DELETE_DATASOURCES);

/**
 * Fork: in the read-only editor (ee/utils/editorReadOnly) nobody creates, changes or deletes pages, queries or JS
 * objects, so the editor shows them the way upstream shows them to a user without those permissions: no "new"
 * entry points, read-only query, API and JS editors, no rename or delete. Running queries is not affected
 * (getHasExecuteActionPermission).
 */
export const getHasCreatePagePermission = (
  isEnabled: boolean,
  permissions?: string[],
) =>
  !isEditorReadOnly() && getHasCreatePagePermission_CE(isEnabled, permissions);

export const getHasManagePagePermission = (
  isEnabled: boolean,
  permissions?: string[],
) =>
  !isEditorReadOnly() && getHasManagePagePermission_CE(isEnabled, permissions);

export const getHasDeletePagePermission = (
  isEnabled: boolean,
  permissions?: string[],
) =>
  !isEditorReadOnly() && getHasDeletePagePermission_CE(isEnabled, permissions);

export const getHasCreateActionPermission = (
  isEnabled: boolean,
  permissions?: string[],
) =>
  !isEditorReadOnly() &&
  getHasCreateActionPermission_CE(isEnabled, permissions);

export const getHasManageActionPermission = (
  isEnabled: boolean,
  permissions?: string[],
) =>
  !isEditorReadOnly() &&
  getHasManageActionPermission_CE(isEnabled, permissions);

export const getHasDeleteActionPermission = (
  isEnabled: boolean,
  permissions?: string[],
) =>
  !isEditorReadOnly() &&
  getHasDeleteActionPermission_CE(isEnabled, permissions);

// Re-declared because the CE version calls the CE getHasCreateActionPermission directly.
export const hasCreateDSActionPermissionInApp = ({
  dsPermissions,
  ideType,
  isEnabled,
  pagePermissions,
}: {
  dsPermissions?: string[];
  ideType?: IDEType;
  isEnabled: boolean;
  pagePermissions?: string[];
}) =>
  !isEditorReadOnly() &&
  (!ideType || ideType === IDE_TYPE.App
    ? getHasCreateDatasourceActionPermission(isEnabled, dsPermissions) &&
      getHasCreateActionPermission_CE(isEnabled, pagePermissions)
    : getHasCreateDatasourceActionPermission(isEnabled, dsPermissions));

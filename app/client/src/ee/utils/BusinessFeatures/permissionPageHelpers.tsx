export * from "ce/utils/BusinessFeatures/permissionPageHelpers";
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

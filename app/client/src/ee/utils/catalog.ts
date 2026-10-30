import type { ApplicationPayload } from "entities/Application";
import { ApplicationVersion } from "ee/actions/applicationActions";
import type { CatalogItem, CatalogSort } from "ee/api/CatalogApi";
import { PERMISSION_TYPE } from "ee/utils/permissionHelpers";
import { isAdminRole, type InstanceRole } from "ee/utils/instanceRoles";

export const CATALOG_SORT_LABELS: Record<CatalogSort, string> = {
  name: "Name",
  lastDeployedAt: "Last published",
  updatedAt: "Last updated",
};

/**
 * Fork: a catalog item in the shape the app card (pages/Applications/ApplicationCard) reads. The catalog sends no
 * permission list, so the card's permissions are derived from what the server says the caller may do: everyone in
 * the catalog may open the app, `canEdit` adds Edit, and Admins may also export it. The server enforces all of it.
 */
export const catalogItemToApplication = (
  item: CatalogItem,
  role?: InstanceRole,
): ApplicationPayload => {
  const userPermissions: string[] = [PERMISSION_TYPE.READ_APPLICATION];

  if (item.canEdit) userPermissions.push(PERMISSION_TYPE.MANAGE_APPLICATION);

  if (isAdminRole(role))
    userPermissions.push(PERMISSION_TYPE.EXPORT_APPLICATION);

  const defaultPageId = item.defaultPageId ?? "";

  return {
    id: item.id,
    baseId: item.baseId || item.id,
    name: item.name,
    color: item.color ?? undefined,
    icon: item.icon ?? undefined,
    description: item.description ?? undefined,
    workspaceId: item.workspaceId,
    defaultPageId,
    // Pages of an app that is not git-connected have their own id as base id; built-in Git is off in the fork.
    defaultBasePageId: defaultPageId,
    userPermissions,
    appIsExample: false,
    slug: item.slug,
    lastDeployedAt: item.lastDeployedAt ?? undefined,
    modifiedAt: item.updatedAt ?? undefined,
    pages: defaultPageId
      ? [
          {
            id: defaultPageId,
            baseId: defaultPageId,
            name: item.defaultPageSlug ?? "",
            isDefault: true,
            slug: item.defaultPageSlug ?? "",
          },
        ]
      : [],
    applicationVersion: ApplicationVersion.SLUG_URL,
  };
};

/** Tags the way the server stores them (ApplicationTags): trimmed, lower case, inner white space collapsed. */
export const normalizeTag = (tag: string) =>
  tag.trim().toLowerCase().replace(/\s+/g, " ");

export const MAX_TAGS = 10;

export const MAX_TAG_LENGTH = 30;

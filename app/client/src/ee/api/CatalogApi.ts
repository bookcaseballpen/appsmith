import type { AxiosPromise } from "axios";
import Api from "api/Api";
import type { ApiResponse } from "api/ApiResponses";
import type { ApprovalStatus } from "ee/api/ApprovalApi";

export type CatalogSort = "name" | "lastDeployedAt" | "updatedAt";

export type CatalogTagMode = "any" | "all";

export interface CatalogQuery {
  search: string;
  tags: string[];
  tagMode: CatalogTagMode;
  sort: CatalogSort;
  page: number;
  size: number;
}

/** One app of the catalog (the server's ApplicationCatalogDTO.Item). Dates are ISO strings. */
export interface CatalogItem {
  id: string;
  baseId: string;
  workspaceId: string;
  name: string;
  description?: string | null;
  slug: string;
  uniqueSlug?: string | null;
  icon?: string | null;
  color?: string | null;
  tags: string[];
  lastDeployedAt?: string | null;
  updatedAt?: string | null;
  defaultPageId?: string | null;
  defaultPageSlug?: string | null;
  approvalStatus?: ApprovalStatus | null;
  canEdit: boolean;
}

export interface CatalogPage {
  items: CatalogItem[];
  total: number;
  page: number;
  size: number;
}

export interface AppTagsResult {
  applicationId: string;
  tags: string[];
}

/**
 * Fork: the home page catalog (one page of the apps the caller can read, filtered, sorted and paged by the server),
 * the tags for the filter chips, and setting an app's tags. See "App tags and catalog" in FORK.md.
 */
export class CatalogApi extends Api {
  static url = "v1/applications";

  static async fetchCatalog(
    query: CatalogQuery,
  ): Promise<AxiosPromise<ApiResponse<CatalogPage>>> {
    const params: Record<string, string | number> = {
      sort: query.sort,
      page: query.page,
      size: query.size,
    };

    if (query.search.trim()) params.search = query.search.trim();

    if (query.tags.length) {
      params.tags = query.tags.join(",");
      params.tagMode = query.tagMode;
    }

    return Api.get(`${CatalogApi.url}/catalog`, params);
  }

  static async fetchTags(): Promise<AxiosPromise<ApiResponse<string[]>>> {
    return Api.get(`${CatalogApi.url}/tags`);
  }

  static async setTags(
    applicationId: string,
    tags: string[],
  ): Promise<AxiosPromise<ApiResponse<AppTagsResult>>> {
    return Api.put(`v1/app-tags/applications/${applicationId}`, { tags });
  }
}

export default CatalogApi;

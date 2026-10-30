package com.appsmith.server.catalog;

import com.appsmith.server.approvals.ApprovalStatus;

import java.util.List;

/**
 * Fork: one page of the home page catalog ({@code GET /api/v1/applications/catalog}): the apps on it, and how many
 * apps match in all.
 */
public record ApplicationCatalogDTO(List<Item> items, long total, int page, int size) {

    /**
     * One app. The view URL is built from {@code slug}, {@code defaultPageSlug} and {@code defaultPageId}
     * ({@code /app/<slug>/<defaultPageSlug>-<defaultPageId>}), or from {@code uniqueSlug} when the app has a static
     * URL. The default page is the live one when the app is live, otherwise the edit copy's. Dates are ISO-8601
     * strings, as in the application object.
     *
     * @param approvalStatus the stored release-approval status (null when there is none, and always for USER); a read
     *     of the approval state may still cancel it, see ApplicationApprovalService
     * @param canEdit the caller is a DEV or ADMIN and may edit the app (for a DEV: holds an EDIT grant on it)
     */
    public record Item(
            String id,
            String baseId,
            String workspaceId,
            String name,
            String description,
            String slug,
            String uniqueSlug,
            String icon,
            String color,
            List<String> tags,
            String lastDeployedAt,
            String updatedAt,
            String defaultPageId,
            String defaultPageSlug,
            ApprovalStatus approvalStatus,
            boolean canEdit) {}

    /** The body of {@code PUT /api/v1/app-tags/applications/{applicationId}}. */
    public record TagsRequest(List<String> tags) {}

    /** An app's tags after they were set. */
    public record TagsResult(String applicationId, List<String> tags) {}
}

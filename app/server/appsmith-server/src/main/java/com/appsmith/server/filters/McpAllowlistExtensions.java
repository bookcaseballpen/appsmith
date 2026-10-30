package com.appsmith.server.filters;

import com.appsmith.server.filters.ce.McpAllowlistExtensionsCE;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static com.appsmith.server.filters.McpAllowRule.rule;

/**
 * Fork: the MCP tools submit_for_approval and get_approval_status, and export_application and import_application (the
 * projects repo's app JSON, edit copy only). Approving and rejecting are for people only, so their routes are never
 * listed here (and the service refuses MCP tokens for them too).
 */
public class McpAllowlistExtensions extends McpAllowlistExtensionsCE {

    @Override
    public List<McpAllowRule> extensionRules() {
        return List.of(
                rule(HttpMethod.GET, "/api/v1/approvals/applications/{applicationId}"),
                rule(HttpMethod.POST, "/api/v1/approvals/applications/{applicationId}/submit"),
                rule(HttpMethod.GET, "/api/v1/projects-repo/applications/{applicationId}"),
                rule(HttpMethod.POST, "/api/v1/projects-repo/applications/import/{workspaceId}"));
    }

    @Override
    public List<String> extensionPathPrefixes() {
        return List.of("/api/v1/approvals", "/api/v1/projects-repo");
    }

    @Override
    public Set<String> extensionReservedSegments() {
        return Set.of("approve", "reject", "pending", "import");
    }
}

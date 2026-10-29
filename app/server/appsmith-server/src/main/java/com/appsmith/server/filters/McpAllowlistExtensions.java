package com.appsmith.server.filters;

import com.appsmith.server.filters.ce.McpAllowlistExtensionsCE;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static com.appsmith.server.filters.McpAllowRule.rule;

/**
 * Fork: the MCP tools submit_for_approval and get_approval_status. Approving and rejecting are for people only, so
 * their routes are never listed here (and the service refuses MCP tokens for them too).
 */
public class McpAllowlistExtensions extends McpAllowlistExtensionsCE {

    @Override
    public List<McpAllowRule> extensionRules() {
        return List.of(
                rule(HttpMethod.GET, "/api/v1/approvals/applications/{applicationId}"),
                rule(HttpMethod.POST, "/api/v1/approvals/applications/{applicationId}/submit"));
    }

    @Override
    public List<String> extensionPathPrefixes() {
        return List.of("/api/v1/approvals");
    }

    @Override
    public Set<String> extensionReservedSegments() {
        return Set.of("approve", "reject", "pending");
    }
}

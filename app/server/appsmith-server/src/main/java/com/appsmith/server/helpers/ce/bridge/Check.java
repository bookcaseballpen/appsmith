package com.appsmith.server.helpers.ce.bridge;

import com.appsmith.external.models.BaseDomain;

import java.util.List;

/**
 * Fork: one condition of a {@link BridgeQuery}: a field check, or a group of queries joined with OR / AND.
 */
public sealed interface Check {

    record Unit(Op op, String key, Object value) implements Check {}

    /** The ACL check: one of {@code permissionGroups} holds {@code permission} in the domain's policy map. */
    record Permission(String permission, List<String> permissionGroups) implements Check {}

    record Or<T extends BaseDomain>(List<BridgeQuery<T>> items) implements Check {}

    record And<T extends BaseDomain>(List<BridgeQuery<T>> items) implements Check {}
}

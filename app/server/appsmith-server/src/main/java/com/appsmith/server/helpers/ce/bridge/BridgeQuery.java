package com.appsmith.server.helpers.ce.bridge;

import com.appsmith.external.models.BaseDomain;
import lombok.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Fork: a database-neutral query, made of {@link Check}s that are all ANDed. It no longer extends Mongo's
 * {@code Criteria}; the PostgreSQL repositories render it to SQL. Keys are field names as in the domain classes, with
 * dots for nested fields (stored as JSON).
 */
@SuppressWarnings("UnusedReturnValue")
public final class BridgeQuery<T extends BaseDomain> {
    final List<Check> checks = new ArrayList<>();

    BridgeQuery() {}

    public List<Check> getChecks() {
        return Collections.unmodifiableList(checks);
    }

    private BridgeQuery<T> add(Op op, String key, Object value) {
        checks.add(new Check.Unit(op, key, value));
        return this;
    }

    public BridgeQuery<T> equal(@NonNull String key, @NonNull String value) {
        return add(Op.EQUAL, key, value);
    }

    public BridgeQuery<T> equal(@NonNull String key, int value) {
        return add(Op.EQUAL, key, value);
    }

    public BridgeQuery<T> notEqual(@NonNull String key, @NonNull String value) {
        return add(Op.NOT_EQUAL, key, value);
    }

    public BridgeQuery<T> notEqual(@NonNull String key, boolean value) {
        return add(Op.NOT_EQUAL, key, value);
    }

    public BridgeQuery<T> equal(@NonNull String key, @NonNull Enum<?> value) {
        return equal(key, value.name());
    }

    public BridgeQuery<T> notEqual(@NonNull String key, @NonNull Enum<?> value) {
        return notEqual(key, value.name());
    }

    public BridgeQuery<T> equalIgnoreCase(@NonNull String key, @NonNull String value) {
        return add(Op.EQ_IGNORE_CASE, key, value);
    }

    /**
     * Prefer using `.isTrue()` or `.isFalse()` instead of this method **if possible**.
     */
    public BridgeQuery<T> equal(@NonNull String key, boolean value) {
        return add(Op.EQUAL, key, value);
    }

    public BridgeQuery<T> searchIgnoreCase(@NonNull String key, @NonNull String needle) {
        if (key.contains(".")) {
            throw new UnsupportedOperationException("Search-ignore-case is not supported for nested fields");
        }
        return add(Op.SEARCH_IGNORE_CASE, key, needle);
    }

    public BridgeQuery<T> in(@NonNull String key, @NonNull Collection<String> value) {
        return add(Op.IN, key, List.copyOf(value));
    }

    public BridgeQuery<T> notIn(@NonNull String needle, @NonNull Collection<String> haystack) {
        return add(Op.NOT_IN, needle, List.copyOf(haystack));
    }

    // Filtering for enums does not work with hibernate even if the field is annotated with @Enumerated(String.class)
    public BridgeQuery<T> enumIn(@NonNull String key, @NonNull Collection<Enum<?>> value) {
        return add(Op.IN, key, value.stream().map(Enum::name).toList());
    }

    public BridgeQuery<T> exists(@NonNull String key) {
        return add(Op.EXISTS, key, null);
    }

    public BridgeQuery<T> notExists(@NonNull String key) {
        return add(Op.NOT_EXISTS, key, null);
    }

    public BridgeQuery<T> isNull(@NonNull String key) {
        return add(Op.IS_NULL, key, null);
    }

    public BridgeQuery<T> isNotNull(@NonNull String key) {
        return add(Op.IS_NOT_NULL, key, null);
    }

    public BridgeQuery<T> isTrue(@NonNull String key) {
        return add(Op.IS_TRUE, key, null);
    }

    public BridgeQuery<T> isFalse(@NonNull String key) {
        return add(Op.IS_FALSE, key, null);
    }

    public BridgeQuery<T> greaterThan(@NonNull String key, @NonNull Instant value) {
        return add(Op.GREATER_THAN, key, value);
    }

    public BridgeQuery<T> lessThan(@NonNull String key, @NonNull Instant value) {
        return add(Op.LESS_THAN, key, value);
    }

    /**
     * Fork: the array at {@code key} has fewer than {@code size} elements (replaces Mongo's "key.N does not exist").
     */
    public BridgeQuery<T> sizeLessThan(@NonNull String key, int size) {
        return add(Op.SIZE_LESS_THAN, key, size);
    }

    /**
     * Fork: a check with any operator and value, for the derived-query engine of the repositories.
     */
    public BridgeQuery<T> check(@NonNull Op op, @NonNull String key, Object value) {
        return add(op, key, value);
    }

    /**
     * Check that the string `needle` is present in the JSON array at `key`.
     */
    public BridgeQuery<T> jsonIn(@NonNull String needle, @NonNull String key) {
        return add(Op.EQUAL, key, needle);
    }

    /**
     * Fork: the ACL check, true when any of {@code permissionGroups} is granted {@code permission} in the policy map.
     */
    public BridgeQuery<T> hasPermission(@NonNull String permission, @NonNull Collection<String> permissionGroups) {
        checks.add(new Check.Permission(permission, List.copyOf(permissionGroups)));
        return this;
    }

    public BridgeQuery<T> and(BridgeQuery<T> item) {
        checks.add(new Check.And<>(List.of(item)));
        return this;
    }

    public boolean isEmpty() {
        return checks.isEmpty();
    }
}

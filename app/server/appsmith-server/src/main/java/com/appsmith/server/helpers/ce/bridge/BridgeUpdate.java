package com.appsmith.server.helpers.ce.bridge;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fork: a database-neutral update, a list of operations on fields (dotted keys for nested JSON fields). It no longer
 * implements Mongo's {@code UpdateDefinition}; the PostgreSQL repositories render it to an SQL {@code UPDATE}.
 */
public class BridgeUpdate {

    public enum Kind {
        SET,
        SET_FROM_FIELD,
        PUSH,
        ADD_TO_SET,
        PULL,
        INC,
    }

    public record Operation(Kind kind, String key, Object value) {}

    private final List<Operation> operations = new ArrayList<>();

    private BridgeUpdate add(Kind kind, String key, Object value) {
        // Like Mongo's Update, a later operation on the same key replaces the earlier one.
        operations.removeIf(op -> op.key().equals(key));
        operations.add(new Operation(kind, key, value));
        return this;
    }

    public List<Operation> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public BridgeUpdate set(@NonNull String key, Object value) {
        return add(Kind.SET, key, value);
    }

    public BridgeUpdate push(@NonNull String key, @NonNull Object value) {
        return add(Kind.PUSH, key, value);
    }

    public BridgeUpdate addToSet(@NonNull String key, @NonNull Object value) {
        return add(Kind.ADD_TO_SET, key, value);
    }

    public BridgeUpdate pull(@NonNull String key, @NonNull Object value) {
        return add(Kind.PULL, key, value);
    }

    /**
     * Set the value of the field `key`, to the current value of the field `valueKey`.
     */
    public BridgeUpdate setToValueFromField(@NonNull String key, @NonNull String valueKey) {
        return add(Kind.SET_FROM_FIELD, key, valueKey);
    }

    public void inc(@NonNull String key) {
        add(Kind.INC, key, 1);
    }
}

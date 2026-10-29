package com.appsmith.server.helpers.ce.bridge;

/**
 * Fork: the operators a {@link BridgeQuery} check can use. They keep MongoDB's matching rules (a check on an array
 * field matches when any element matches; a negated check also matches documents without the field), so the
 * repositories written against the Mongo version keep their meaning on PostgreSQL.
 */
public enum Op {
    EQUAL,
    NOT_EQUAL,
    EQ_IGNORE_CASE,
    SEARCH_IGNORE_CASE,
    IN,
    NOT_IN,
    EXISTS,
    NOT_EXISTS,
    IS_NULL,
    IS_NOT_NULL,
    IS_TRUE,
    IS_FALSE,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
}

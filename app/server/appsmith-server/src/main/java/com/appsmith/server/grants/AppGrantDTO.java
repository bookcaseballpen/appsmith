package com.appsmith.server.grants;

/**
 * One grant on an app, as {@code GET /api/v1/applications/{id}/grants} lists it. {@code name} is the team name or the
 * user's email.
 */
public record AppGrantDTO(Grantee grantee, String name, GrantLevel level) {

    /**
     * Who a grant goes to: a team (its id) or a single user (the user id).
     */
    public record Grantee(Type type, String id) {}

    public enum Type {
        TEAM,
        USER
    }

    /** The body of {@code PUT /api/v1/applications/{id}/grants}. */
    public record Request(Grantee grantee, GrantLevel level) {}
}

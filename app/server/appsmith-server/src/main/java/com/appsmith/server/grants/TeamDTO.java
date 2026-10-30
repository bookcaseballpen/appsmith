package com.appsmith.server.grants;

import com.appsmith.server.domains.PermissionGroup;
import com.appsmith.server.domains.User;

import java.util.List;

/**
 * A team as the teams API returns it, with its members.
 */
public record TeamDTO(String id, String name, String description, List<Member> members) {

    public record Member(String id, String email, String name) {

        static Member of(User user) {
            return new Member(user.getId(), user.getEmail(), user.getName());
        }
    }

    /** The body of {@code POST /api/v1/teams} and {@code PUT /api/v1/teams/{id}}. */
    public record Request(String name, String description) {}

    static TeamDTO of(PermissionGroup team, List<Member> members) {
        return new TeamDTO(team.getId(), team.getName(), team.getDescription(), members);
    }
}

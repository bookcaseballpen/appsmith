package com.appsmith.server.grants;

import java.util.List;

/**
 * The two kinds of permission group an app grant goes to, told apart by {@code PermissionGroup.defaultDomainType}:
 * <ul>
 *   <li>{@value #TEAM}: a team an administrator manages ({@link TeamService}); its members are the users in
 *       {@code assignedToUserIds}.</li>
 *   <li>{@value #USER_GRANT}: a user's hidden personal group ({@code defaultDomainId} is the user id, the user is its
 *       only member), created on the user's first grant, because document policies only name permission groups.</li>
 * </ul>
 * Both are resolved as the user's permission groups (see {@code CacheableRepositoryHelperCEImpl}) and hold no
 * permission of their own: only app policies name them.
 */
public final class GrantGroups {

    public static final String TEAM = "Team";
    public static final String USER_GRANT = "UserGrant";
    public static final List<String> TYPES = List.of(TEAM, USER_GRANT);

    private GrantGroups() {}
}

package com.appsmith.server.grants;

/**
 * What an app grant lets its grantee do.
 * <ul>
 *   <li>VIEW: read the app and run its queries and JS objects (its pages, queries, JS objects and theme follow).</li>
 *   <li>EDIT: VIEW plus change the app and its pages, queries and JS objects. Never publish: an app goes live only
 *       through the release approval flow. A USER-role grantee gets VIEW out of it (see
 *       {@link com.appsmith.server.roles.RoleGate}).</li>
 *   <li>NONE: no grant; setting it revokes one.</li>
 * </ul>
 */
public enum GrantLevel {
    NONE,
    VIEW,
    EDIT
}

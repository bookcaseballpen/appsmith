-- Fork: the PostgreSQL baseline schema (see FORK.md, "PostgreSQL").
--
-- Generated from the JPA entities with Hibernate's schema export, then edited:
--   * text instead of varchar(255) and no NOT NULL besides the keys, because MongoDB had no length limits or
--     required fields, and the code was written for that;
--   * no CHECK constraints on enum columns, so that a new enum value needs no migration;
--   * nested objects and lists are jsonb (the entities' @Type(CustomJsonType.class) fields);
--   * the lookup and unique indexes the Mongock migrations created on MongoDB, as far as the CE code relies on them.
-- Flyway runs this in the "appsmith" schema; V2 loads the fresh-install data.

CREATE TABLE action_collection (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    git_sync_id text,
    application_id text,
    workspace_id text,
    base_id text,
    branch_name text,
    ref_type text,
    ref_name text,
    context_type text,
    unpublished_collection jsonb,
    published_collection jsonb
);

CREATE TABLE application (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    workspace_id text,
    is_public boolean,
    pages jsonb,
    published_pages jsonb,
    cloned_from_application_id text,
    unpublished_application_detail jsonb,
    published_application_detail jsonb,
    color text,
    icon text,
    description text,
    slug text,
    static_url_settings jsonb,
    unpublished_app_layout jsonb,
    published_app_layout jsonb,
    unpublished_custom_js_libs jsonb,
    published_custom_js_libs jsonb,
    git_application_metadata jsonb,
    last_deployed_at timestamptz,
    evaluation_version integer,
    application_version integer,
    last_edited_at timestamptz,
    embed_setting jsonb,
    collapse_invisible_widgets boolean,
    forking_enabled boolean,
    is_manual_update boolean,
    client_schema_version integer,
    server_schema_version integer,
    edit_mode_theme_id text,
    published_mode_theme_id text,
    export_with_configuration boolean,
    fork_with_configuration boolean,
    forked_from_template_title text,
    is_community_template boolean
);

CREATE TABLE application_snapshot (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    application_id text,
    chunk_order integer,
    data bytea
);

CREATE TABLE asset (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    content_type text,
    data bytea
);

CREATE TABLE collection (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    application_id text,
    workspace_id text,
    shared boolean,
    actions jsonb
);

CREATE TABLE config (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    config jsonb
);

CREATE TABLE custom_js_lib (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    accessor jsonb,
    url text,
    version text,
    docs_url text,
    defs text,
    uid_string text
);

CREATE TABLE datasource (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    git_sync_id text,
    name text,
    plugin_id text,
    workspace_id text,
    template_name text,
    datasource_configuration jsonb,
    invalids jsonb,
    is_configured boolean,
    is_template boolean,
    is_mock boolean,
    has_datasource_storage boolean
);

CREATE TABLE datasource_storage (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    git_sync_id text,
    datasource_id text,
    environment_id text,
    datasource_configuration jsonb,
    is_configured boolean,
    invalids jsonb
);

CREATE TABLE datasource_storage_structure (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    datasource_id text,
    environment_id text,
    structure jsonb
);

CREATE TABLE email_verification_token (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    organization_id text,
    email text,
    token_hash text,
    token_generated_at timestamptz
);

CREATE TABLE git_deploy_keys (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    email text,
    git_auth jsonb
);

CREATE TABLE mcp_key (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    user_id text,
    name text,
    key_span_days integer,
    revoked_at timestamptz
);

CREATE TABLE mcp_token (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    mcp_key_id text,
    user_id text,
    token_hash text,
    status text,
    expires_at timestamptz,
    acted_at timestamptz
);

CREATE TABLE new_action (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    git_sync_id text,
    application_id text,
    workspace_id text,
    plugin_type text,
    plugin_id text,
    documentation jsonb,
    base_id text,
    branch_name text,
    ref_type text,
    ref_name text,
    unpublished_action jsonb,
    published_action jsonb
);

CREATE TABLE new_page (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    git_sync_id text,
    application_id text,
    base_id text,
    branch_name text,
    ref_type text,
    ref_name text,
    unpublished_page jsonb,
    published_page jsonb
);

CREATE TABLE organization (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    display_name text,
    slug text,
    pricing_plan text,
    organization_configuration jsonb
);

CREATE TABLE password_reset_token (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    organization_id text,
    email text,
    token_hash text,
    request_count integer,
    first_request_time timestamptz
);

CREATE TABLE permission_group (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    description text,
    organization_id text,
    tenant_id text,
    default_domain_id text,
    default_domain_type text,
    default_workspace_id text,
    permissions jsonb,
    assigned_to_user_ids jsonb,
    assigned_to_group_ids jsonb
);

CREATE TABLE plugin (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    type text,
    package_name text,
    plugin_name text,
    jar_location text,
    icon_location text,
    documentation_link text,
    response_type text,
    ui_component text,
    datasource_component text,
    action_component text,
    generate_crud_page_component text,
    datasource_params jsonb,
    action_params jsonb,
    datasource_ui_config jsonb,
    action_ui_config jsonb,
    min_appsmith_version_supported text,
    max_appsmith_version_supported text,
    version text,
    default_install boolean,
    allow_user_datasources boolean,
    is_remote_plugin boolean,
    is_supported_for_air_gap boolean,
    is_dependent_on_cs boolean
);

CREATE TABLE sequence (
    name text PRIMARY KEY,
    next_number bigint
);

CREATE TABLE tenant (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    display_name text,
    slug text,
    pricing_plan text,
    tenant_configuration jsonb
);

CREATE TABLE theme (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    display_name text,
    application_id text,
    workspace_id text,
    is_system_theme boolean,
    config jsonb,
    properties jsonb,
    stylesheet jsonb
);

CREATE TABLE usage_pulse (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    email text,
    "user" text,
    instance_id text,
    organization_id text,
    tenant_id text,
    view_mode boolean,
    is_anonymous_user boolean
);

CREATE TABLE "user" (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    name text,
    email text,
    hashed_email text,
    source text,
    state text,
    is_enabled boolean,
    password text,
    workspace_ids jsonb,
    examples_workspace_id text,
    organization_id text,
    tenant_id text,
    email_verified boolean,
    email_verification_required boolean,
    password_reset_initiated boolean,
    invite_token text,
    is_anonymous boolean,
    is_system_generated boolean,
    last_active_at timestamptz
);

CREATE TABLE user_data (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    user_id text,
    proficiency text,
    use_case text,
    profile_photo_asset_id text,
    release_notes_viewed_version text,
    is_intercom_consent_given boolean,
    recently_used_workspace_ids jsonb,
    recently_used_app_ids jsonb,
    recently_used_entity_ids jsonb,
    favorite_application_ids jsonb,
    git_profiles jsonb,
    user_claims jsonb
);

CREATE TABLE workspace (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    domain text,
    name text,
    website text,
    email text,
    plugins jsonb,
    slug text,
    is_auto_generated_workspace boolean,
    logo_asset_id text,
    organization_id text,
    tenant_id text,
    has_environments boolean,
    default_permission_groups jsonb
);

-- The first-admin signup claims this row (INSERT ... ON CONFLICT DO NOTHING); see CustomUserRepositoryCEImpl.
CREATE TABLE super_user_setup_lock (
    id text PRIMARY KEY,
    status text,
    claimed_at text,
    source text
);

-- Unique keys the CE code relies on (Mongo had unique indexes on them).
CREATE UNIQUE INDEX config_name_uk ON config (name);
CREATE UNIQUE INDEX organization_slug_uk ON organization (slug);
CREATE UNIQUE INDEX plugin_package_name_uk ON plugin (package_name);
CREATE UNIQUE INDEX user_email_organization_uk ON "user" (email, organization_id);
CREATE UNIQUE INDEX user_data_user_id_uk ON user_data (user_id);
CREATE UNIQUE INDEX mcp_token_token_hash_uk ON mcp_token (token_hash);

-- Lookups.
CREATE INDEX action_collection_application_id_idx ON action_collection (application_id, deleted_at);
CREATE INDEX action_collection_base_id_idx ON action_collection (base_id, branch_name);
CREATE INDEX application_workspace_id_idx ON application (workspace_id, deleted_at);
CREATE INDEX application_snapshot_application_id_idx ON application_snapshot (application_id, chunk_order);
CREATE INDEX custom_js_lib_uid_string_idx ON custom_js_lib (uid_string);
CREATE INDEX datasource_workspace_id_idx ON datasource (workspace_id, deleted_at);
CREATE INDEX datasource_storage_datasource_id_idx ON datasource_storage (datasource_id, environment_id);
CREATE INDEX datasource_storage_structure_datasource_id_idx
    ON datasource_storage_structure (datasource_id, environment_id);
CREATE INDEX mcp_key_user_id_idx ON mcp_key (user_id);
CREATE INDEX mcp_token_mcp_key_id_idx ON mcp_token (mcp_key_id, status);
CREATE INDEX new_action_application_id_idx ON new_action (application_id, deleted_at);
CREATE INDEX new_action_base_id_idx ON new_action (base_id, branch_name);
CREATE INDEX new_page_application_id_idx ON new_page (application_id, deleted_at);
CREATE INDEX new_page_base_id_idx ON new_page (base_id, branch_name);
CREATE INDEX permission_group_default_domain_idx ON permission_group (default_domain_id, default_domain_type);
CREATE INDEX permission_group_assigned_to_user_ids_idx ON permission_group USING gin (assigned_to_user_ids);
CREATE INDEX theme_application_id_idx ON theme (application_id);
CREATE INDEX workspace_organization_id_idx ON workspace (organization_id);

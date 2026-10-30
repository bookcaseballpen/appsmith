-- Fork: the rest of the unique indexes the Mongo version had (Mongock changelogs and migrations 005, 012, 014, 016,
-- 074), so that the same writes fail with a duplicate key. Mongo names are kept. NULLS NOT DISTINCT matches Mongo,
-- where missing values compare equal; a Mongo partial filter "{field: {$exists: true}}" becomes "field IS NOT NULL".

-- One cached structure per datasource and environment (dsConfigStructure_dsId_envId); replaces the plain index.
DROP INDEX datasource_storage_structure_datasource_id_idx;
CREATE UNIQUE INDEX "dsConfigStructure_dsId_envId" ON datasource_storage_structure (
    datasource_id,
    environment_id
) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX datasource_storage_compound_index ON datasource_storage (
    datasource_id,
    environment_id,
    deleted_at
) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX "applicationId_chunkOrder_unique_index" ON application_snapshot (
    application_id,
    chunk_order
) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX customjslibs_uidstring_index ON custom_js_lib (uid_string) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX password_reset_token_email_uk ON password_reset_token (email) NULLS NOT DISTINCT;

-- Static URL slugs (migration 074).
CREATE UNIQUE INDEX "appId_edit_uniqSlug_dltdAt" ON new_page (
    application_id,
    (unpublished_page ->> 'uniqueSlug'),
    (unpublished_page ->> 'deletedAt'),
    deleted_at
) NULLS NOT DISTINCT
WHERE (unpublished_page ->> 'uniqueSlug') IS NOT NULL;

CREATE UNIQUE INDEX "appId_view_uniqSlug_dltdAt" ON new_page (
    application_id,
    (published_page ->> 'uniqueSlug'),
    deleted_at
) NULLS NOT DISTINCT
WHERE (published_page ->> 'uniqueSlug') IS NOT NULL;

CREATE UNIQUE INDEX "defAppId_uniqSlug_brnch_dltdAt" ON application (
    (static_url_settings ->> 'uniqueSlug'),
    deleted_at,
    (git_application_metadata ->> 'defaultApplicationId'),
    (git_application_metadata ->> 'branchName')
) NULLS NOT DISTINCT
WHERE (static_url_settings ->> 'uniqueSlug') IS NOT NULL;

-- Fork: unique indexes that the Mongo version had and that the services rely on. Creating an application or a
-- datasource with a name that is taken in the workspace fails with a duplicate key, and the services retry with a
-- suffix ("App (1)") or report the clash. They recognise the index by its name in the error message, so the Mongo
-- names are kept (quoted, to keep their case). NULLS NOT DISTINCT matches Mongo, where missing values compare equal
-- (PostgreSQL 15 or later).

CREATE UNIQUE INDEX "workspace_app_deleted_gitApplicationMetadata" ON application (
    workspace_id,
    name,
    deleted_at,
    (git_application_metadata ->> 'remoteUrl'),
    (git_application_metadata ->> 'branchName')
) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX workspace_datasource_deleted_compound_index ON datasource (
    workspace_id,
    name,
    deleted_at
) NULLS NOT DISTINCT;

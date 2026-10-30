-- Fork: app tags for the home page catalog (ApplicationCatalogService), and one live team per name.

-- A JSON array of lower-cased strings (ApplicationTags). The Mongo version indexed tags + deletedAt (Migration077);
-- here the catalog's tag filters render as "tags @? '$[*] ? (@ == ...)'", which this index serves.
ALTER TABLE application ADD COLUMN tags jsonb;
CREATE INDEX application_tags_idx ON application USING gin (tags jsonb_path_ops);

-- TeamService checks the name (ignoring case) before and after writing; the index also stops two administrators who
-- name two teams the same at once. Deleted teams keep their name, so it may be reused.
CREATE UNIQUE INDEX permission_group_team_name_uk ON permission_group (lower(name))
WHERE default_domain_type = 'Team' AND deleted_at IS NULL;

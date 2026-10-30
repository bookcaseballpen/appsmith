-- Fork: V2 mapped field names with an acronym (generateCRUDPageComponent, isDependentOnCS) to columns that do not
-- exist, so a database seeded before the fix has these plugin columns empty. Values are those of
-- db/seed/fresh-install.json; a database seeded after the fix already has them and is left as it is.

UPDATE plugin SET generate_crud_page_component = 'PostgreSQL'
WHERE package_name = 'postgres-plugin' AND generate_crud_page_component IS NULL;

UPDATE plugin SET generate_crud_page_component = 'MongoDB'
WHERE package_name = 'mongo-plugin' AND generate_crud_page_component IS NULL;

UPDATE plugin SET generate_crud_page_component = 'SQL'
WHERE package_name IN ('mysql-plugin', 'mssql-plugin', 'redshift-plugin', 'snowflake-plugin')
    AND generate_crud_page_component IS NULL;

UPDATE plugin SET generate_crud_page_component = 'S3'
WHERE package_name = 'amazons3-plugin' AND generate_crud_page_component IS NULL;

UPDATE plugin SET generate_crud_page_component = 'Google Sheets', is_dependent_on_cs = COALESCE(is_dependent_on_cs, true)
WHERE package_name = 'google-sheets-plugin' AND generate_crud_page_component IS NULL;

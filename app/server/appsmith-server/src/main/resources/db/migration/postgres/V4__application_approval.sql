-- Fork: the release approval state of an application (task #284). Its id is the application's id.
CREATE TABLE application_approval (
    id text PRIMARY KEY,
    created_at timestamptz,
    updated_at timestamptz,
    deleted_at timestamptz,
    created_by text,
    modified_by text,
    policy_map jsonb,
    application_id text,
    workspace_id text,
    status text,
    fingerprint text,
    submitted_by text,
    submitted_at timestamptz,
    reviewed_by text,
    reviewed_at timestamptz,
    comment text,
    revision integer,
    history jsonb
);

-- The pending list reads the submitted requests.
CREATE INDEX application_approval_status_idx ON application_approval (status);

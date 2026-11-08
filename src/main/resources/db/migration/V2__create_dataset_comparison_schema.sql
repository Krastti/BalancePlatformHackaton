CREATE TABLE source_datasets (
    id uuid PRIMARY KEY,
    source_run_id text NOT NULL UNIQUE,
    name text NOT NULL,
    application_count bigint NOT NULL CHECK (application_count >= 0)
);

CREATE TABLE dataset_comparisons (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES source_datasets(id),
    status text NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    error_code text,
    error_message text,
    total_applications bigint,
    applications_with_differences bigint,
    applications_with_decision_changes bigint
);

CREATE INDEX dataset_comparisons_history ON dataset_comparisons (created_at DESC, id);
CREATE INDEX dataset_comparisons_pending ON dataset_comparisons (created_at, id) WHERE status = 'PENDING';

CREATE TABLE dataset_comparison_applications (
    comparison_id uuid NOT NULL REFERENCES dataset_comparisons(id),
    application_id text COLLATE "C" NOT NULL CHECK (application_id <> ''),
    decision_before text NOT NULL CHECK (decision_before IN ('APPROVE', 'REJECT')),
    decision_after text NOT NULL CHECK (decision_after IN ('APPROVE', 'REJECT')),
    has_diff boolean NOT NULL,
    decision_changed boolean NOT NULL,
    CHECK (decision_changed = (decision_before <> decision_after)),
    PRIMARY KEY (comparison_id, application_id)
);

CREATE TABLE dataset_comparison_differences (
    comparison_id uuid NOT NULL,
    application_id text COLLATE "C" NOT NULL,
    path text COLLATE "C" NOT NULL CHECK (path <> ''),
    array_key text COLLATE "C" NOT NULL,
    value_before text,
    value_after text,
    change_type text NOT NULL CHECK (change_type IN ('VALUE_CHANGED', 'ATTRIBUTE_NEW', 'ATTRIBUTE_LOST')),
    CHECK ((value_before IS NULL) = (change_type = 'ATTRIBUTE_NEW')),
    CHECK ((value_after IS NULL) = (change_type = 'ATTRIBUTE_LOST')),
    PRIMARY KEY (comparison_id, application_id, path, array_key),
    FOREIGN KEY (comparison_id, application_id)
        REFERENCES dataset_comparison_applications(comparison_id, application_id)
);

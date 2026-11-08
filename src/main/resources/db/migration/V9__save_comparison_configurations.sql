CREATE TABLE dataset_comparison_configurations (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    name text NOT NULL,
    dataset_id uuid NOT NULL REFERENCES source_datasets(id),
    checks jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX dataset_comparison_configurations_workspace
    ON dataset_comparison_configurations (workspace_id, updated_at DESC, id);

ALTER TABLE dataset_comparisons ADD COLUMN configuration_snapshot jsonb;

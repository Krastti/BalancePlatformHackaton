ALTER TABLE dataset_comparisons ADD COLUMN workspace_id uuid;

CREATE INDEX dataset_comparisons_workspace_history
    ON dataset_comparisons (workspace_id, created_at DESC, id);

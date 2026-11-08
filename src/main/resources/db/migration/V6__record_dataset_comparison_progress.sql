ALTER TABLE dataset_comparisons
    ADD COLUMN journal_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN checks_started_at timestamptz,
    ADD COLUMN import_started_at timestamptz;

ALTER TABLE dataset_comparisons ALTER COLUMN journal_enabled SET DEFAULT true;

ALTER TABLE dataset_comparisons ADD COLUMN custom_pivot_membership boolean NOT NULL DEFAULT false;

CREATE TABLE dataset_pivot_membership (
    comparison_id uuid NOT NULL,
    application_id text COLLATE "C" NOT NULL,
    path text COLLATE "C" NOT NULL,
    array_key text COLLATE "C" NOT NULL,
    source text NOT NULL CHECK (source IN ('BEFORE', 'AFTER')),
    PRIMARY KEY (comparison_id, application_id, path, array_key, source),
    FOREIGN KEY (comparison_id, application_id, path, array_key)
        REFERENCES dataset_comparison_attributes (comparison_id, application_id, path, array_key)
);

CREATE VIEW dataset_pivot_slices AS
SELECT a.*
FROM dataset_comparison_slices a
JOIN dataset_comparisons r ON r.id = a.comparison_id
WHERE NOT r.custom_pivot_membership
UNION ALL
SELECT a.*
FROM dataset_pivot_membership f
JOIN dataset_comparison_slices a
    USING (comparison_id, application_id, path, array_key, source);

ALTER TABLE dataset_comparison_configurations
    ADD COLUMN facts_procedure_name text,
    ADD COLUMN facts_code text;

ALTER TABLE dataset_comparisons ADD COLUMN facts_started_at timestamptz;

ALTER TABLE dataset_comparison_stage_sources
    DROP CONSTRAINT dataset_comparison_stage_sources_stage_check,
    ADD CONSTRAINT dataset_comparison_stage_sources_stage_check
        CHECK (stage IN ('NORMALIZATION', 'COMPARISON', 'FACTS', 'CHECKS'));

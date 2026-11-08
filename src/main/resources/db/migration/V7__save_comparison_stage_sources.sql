CREATE TABLE dataset_comparison_stage_sources (
    comparison_id uuid NOT NULL REFERENCES dataset_comparisons(id),
    stage text NOT NULL CHECK (stage IN ('COMPARISON', 'CHECKS')),
    sources jsonb NOT NULL,
    PRIMARY KEY (comparison_id, stage)
);

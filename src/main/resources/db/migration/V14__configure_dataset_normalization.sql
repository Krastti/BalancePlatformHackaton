ALTER TABLE dataset_comparison_configurations
    ADD COLUMN normalizer_name text,
    ADD COLUMN normalizer_code text;

ALTER TABLE dataset_comparisons ADD COLUMN comparison_started_at timestamptz;

ALTER TABLE dataset_comparison_stage_sources
    DROP CONSTRAINT dataset_comparison_stage_sources_stage_check,
    ADD CONSTRAINT dataset_comparison_stage_sources_stage_check
        CHECK (stage IN ('NORMALIZATION', 'COMPARISON', 'CHECKS'));

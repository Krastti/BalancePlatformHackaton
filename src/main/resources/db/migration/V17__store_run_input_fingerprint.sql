ALTER TABLE dataset_comparisons
    ADD COLUMN input_sha256 text,
    ADD COLUMN expected_input_sha256 text,
    ADD COLUMN runtime_sha256 text,
    ADD COLUMN expected_runtime_sha256 text;

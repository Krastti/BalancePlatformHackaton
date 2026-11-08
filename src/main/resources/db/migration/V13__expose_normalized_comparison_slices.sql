CREATE VIEW dataset_comparison_slices AS
SELECT a.comparison_id, a.application_id, a.path, a.prefix, a.attribute_class, a.suffix,
       a.array_key, a.field_key, slice.source, slice.value, slice.adjusted_value,
       a.change_type, a.change_type <> 'SAME' AS diff_flag, a.value_before, a.value_after
FROM dataset_comparison_attributes a
CROSS JOIN LATERAL (VALUES
    ('BEFORE', a.value_before, a.adjusted_before),
    ('AFTER', a.value_after, a.adjusted_after)
) slice(source, value, adjusted_value)
WHERE slice.value IS NOT NULL;

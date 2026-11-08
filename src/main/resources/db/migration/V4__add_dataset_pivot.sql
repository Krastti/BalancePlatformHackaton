ALTER TABLE dataset_comparisons ADD COLUMN pivot_available boolean NOT NULL DEFAULT false;

CREATE TABLE dataset_comparison_attributes (
    comparison_id uuid NOT NULL,
    application_id text COLLATE "C" NOT NULL,
    path text COLLATE "C" NOT NULL CHECK (path <> ''),
    array_key text COLLATE "C" NOT NULL,
    prefix text NOT NULL,
    attribute_class text NOT NULL,
    suffix text NOT NULL,
    field_key text NOT NULL,
    value_before text,
    value_after text,
    adjusted_before text,
    adjusted_after text,
    change_type text NOT NULL CHECK (change_type IN ('SAME', 'VALUE_CHANGED', 'ATTRIBUTE_NEW', 'ATTRIBUTE_LOST')),
    PRIMARY KEY (comparison_id, application_id, path, array_key),
    FOREIGN KEY (comparison_id, application_id)
        REFERENCES dataset_comparison_applications(comparison_id, application_id)
);

CREATE INDEX dataset_comparison_attributes_path ON dataset_comparison_attributes (comparison_id, path);

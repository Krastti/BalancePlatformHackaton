CREATE TABLE dataset_comparison_segments (
    comparison_id uuid NOT NULL,
    application_id text COLLATE "C" NOT NULL,
    products text NOT NULL,
    partner_types text NOT NULL,
    routing_methods text NOT NULL,
    top_offer_before text,
    top_offer_after text,
    pd_model_before text,
    pd_model_after text,
    cutoff_before text,
    cutoff_after text,
    reject_reason_before text,
    reject_reason_after text,
    score_okb_before text,
    score_okb_after text,
    PRIMARY KEY (comparison_id, application_id),
    FOREIGN KEY (comparison_id, application_id)
        REFERENCES dataset_comparison_applications(comparison_id, application_id)
);

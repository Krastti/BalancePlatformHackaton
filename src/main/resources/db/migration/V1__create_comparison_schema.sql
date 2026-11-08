CREATE TABLE application_datasets (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(name) > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE historical_applications (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES application_datasets(id),
    external_id TEXT NOT NULL CHECK (length(external_id) > 0),
    features JSONB NOT NULL CHECK (jsonb_typeof(features) = 'object'),
    CONSTRAINT uq_dataset_external_id UNIQUE (dataset_id, external_id)
);

CREATE TABLE strategy_versions (
    id UUID PRIMARY KEY,
    strategy_key TEXT NOT NULL CHECK (length(strategy_key) > 0),
    version TEXT NOT NULL CHECK (length(version) > 0),
    name TEXT NOT NULL CHECK (length(name) > 0),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_strategy_version UNIQUE (strategy_key, version)
);

CREATE TABLE comparison_runs (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES application_datasets(id),
    old_version_id UUID NOT NULL REFERENCES strategy_versions(id),
    new_version_id UUID NOT NULL REFERENCES strategy_versions(id),
    executor_version TEXT NOT NULL CHECK (length(executor_version) > 0),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    old_execution_status TEXT NOT NULL CHECK (old_execution_status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    new_execution_status TEXT NOT NULL CHECK (new_execution_status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    error_details JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CHECK (status <> 'COMPLETED' OR (old_execution_status = 'COMPLETED' AND new_execution_status = 'COMPLETED')),
    CHECK ((status IN ('COMPLETED', 'PARTIAL', 'FAILED')) = (finished_at IS NOT NULL)),
    CHECK (status <> 'RUNNING' OR started_at IS NOT NULL),
    CHECK (status <> 'FAILED' OR error_details IS NOT NULL),
    CHECK (error_details IS NULL OR (jsonb_typeof(error_details) = 'object'
        AND error_details ? 'code' AND error_details ? 'message'
        AND length(error_details->>'code') > 0 AND length(error_details->>'message') > 0))
);

CREATE TABLE application_results (
    id UUID PRIMARY KEY,
    comparison_run_id UUID NOT NULL REFERENCES comparison_runs(id),
    application_id UUID NOT NULL REFERENCES historical_applications(id),
    side TEXT NOT NULL CHECK (side IN ('OLD', 'NEW')),
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED', 'ERROR')),
    decision TEXT CHECK (decision IN ('APPROVE', 'DECLINE')),
    error_details JSONB,
    trace JSONB NOT NULL CHECK (jsonb_typeof(trace) = 'object'
        AND trace ? 'steps' AND jsonb_typeof(trace->'steps') = 'array'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_run_application_side UNIQUE (comparison_run_id, application_id, side),
    CHECK ((status = 'SUCCEEDED' AND decision IS NOT NULL AND error_details IS NULL)
        OR (status = 'ERROR' AND decision IS NULL AND error_details IS NOT NULL)),
    CHECK (error_details IS NULL OR (jsonb_typeof(error_details) = 'object'
        AND error_details ? 'code' AND error_details ? 'message'
        AND length(error_details->>'code') > 0 AND length(error_details->>'message') > 0))
);

CREATE INDEX ix_datasets_page ON application_datasets (created_at DESC, id ASC);
CREATE INDEX ix_strategies_page ON strategy_versions (created_at DESC, id ASC);
CREATE INDEX ix_strategies_family_page ON strategy_versions (strategy_key, created_at DESC, id ASC);
CREATE INDEX ix_runs_page ON comparison_runs (created_at DESC, id ASC);
CREATE INDEX ix_runs_queue ON comparison_runs (status, created_at, id);
CREATE INDEX ix_applications_dataset ON historical_applications (dataset_id, id);

-- Исходные снимки и сохранённые результаты не редактируются и не удаляются.
CREATE FUNCTION reject_immutable_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable data cannot be changed' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_datasets BEFORE UPDATE OR DELETE ON application_datasets
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER immutable_strategies BEFORE UPDATE OR DELETE ON strategy_versions
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER immutable_applications BEFORE UPDATE OR DELETE ON historical_applications
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER immutable_results BEFORE UPDATE OR DELETE ON application_results
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();
CREATE TRIGGER retained_runs BEFORE DELETE ON comparison_runs
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_change();

-- Блокировка набора синхронизирует загрузку заявок с созданием первого запуска.
CREATE FUNCTION protect_dataset_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM application_datasets WHERE id = NEW.dataset_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM comparison_runs WHERE dataset_id = NEW.dataset_id) THEN
        RAISE EXCEPTION 'Dataset is already used by a comparison' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER frozen_dataset_membership BEFORE INSERT ON historical_applications
    FOR EACH ROW EXECUTE FUNCTION protect_dataset_membership();

CREATE FUNCTION protect_comparison_inputs() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM application_datasets WHERE id = NEW.dataset_id FOR UPDATE;
    ELSIF (OLD.dataset_id, OLD.old_version_id, OLD.new_version_id, OLD.executor_version, OLD.created_at)
        IS DISTINCT FROM
        (NEW.dataset_id, NEW.old_version_id, NEW.new_version_id, NEW.executor_version, NEW.created_at) THEN
        RAISE EXCEPTION 'Comparison inputs are immutable' USING ERRCODE = '23514';
    ELSIF OLD.status IN ('COMPLETED', 'PARTIAL', 'FAILED') THEN
        RAISE EXCEPTION 'Finished comparison is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'COMPLETED' AND (
        SELECT count(*) FROM application_results
        WHERE comparison_run_id = NEW.id AND status = 'SUCCEEDED'
    ) <> 2 * (SELECT count(*) FROM historical_applications WHERE dataset_id = NEW.dataset_id) THEN
        RAISE EXCEPTION 'Completed comparison requires two successful results per application' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_comparison_inputs BEFORE INSERT OR UPDATE ON comparison_runs
    FOR EACH ROW EXECUTE FUNCTION protect_comparison_inputs();

CREATE FUNCTION check_result_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM comparison_runs run
        JOIN historical_applications application ON application.dataset_id = run.dataset_id
        WHERE run.id = NEW.comparison_run_id AND application.id = NEW.application_id AND run.status = 'RUNNING'
    ) THEN
        RAISE EXCEPTION 'Application must belong to the running comparison dataset' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER matching_result_dataset BEFORE INSERT ON application_results
    FOR EACH ROW EXECUTE FUNCTION check_result_membership();

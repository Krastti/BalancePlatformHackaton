package com.kratti.repository;

import com.kratti.dto.response.ApiError;
import com.kratti.dto.response.DatasetComparisonViews.Application;
import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import com.kratti.dto.response.DatasetComparisonViews.Decision;
import com.kratti.dto.response.DatasetComparisonViews.Difference;
import com.kratti.dto.response.DatasetComparisonViews.Matrix;
import com.kratti.dto.response.DatasetComparisonViews.Report;
import com.kratti.dto.response.DatasetComparisonViews.Run;
import com.kratti.dto.response.DatasetComparisonViews.RunInput;
import com.kratti.dto.response.DatasetComparisonViews.Stage;
import com.kratti.dto.response.DatasetComparisonViews.StageName;
import com.kratti.dto.response.DatasetComparisonViews.StageStatus;
import com.kratti.dto.response.DatasetConfigurationViews.Configuration;
import com.kratti.dto.response.DatasetConfigurationViews.Snapshot;
import com.kratti.enums.RunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Хранит очередь и предоставляет выборки для отчётов по ответам СПР. */
@Repository
@RequiredArgsConstructor
public class DatasetComparisonRepository {
    private static final String RUN_COLUMNS = """
            id, dataset_id, status, created_at, started_at, finished_at, error_code, error_message,
            journal_enabled, comparison_started_at, checks_started_at, facts_started_at, import_started_at,
            input_sha256, expected_input_sha256, runtime_sha256, expected_runtime_sha256,
            (SELECT source_run_id FROM source_datasets s WHERE s.id = dataset_id) AS input_source_id,
            (configuration_snapshot #>> '{analysis,schemaVersion}')::integer AS configuration_version,
            COALESCE(configuration_snapshot #>> '{configuration,normalizer}' IS NOT NULL
                OR configuration_snapshot #>> '{configuration,normalizerCode}' IS NOT NULL, false) AS normalizer_enabled,
            (configuration_snapshot #>> '{configuration,factsProcedure}' IS NOT NULL
                OR configuration_snapshot #>> '{configuration,factsCode}' IS NOT NULL) AS facts_enabled,
            COALESCE(configuration_snapshot #>> '{analysis,stages,2,type}' = 'FACTS', false) AS facts_first,
            (configuration_snapshot -> 'configuration') - 'factsCode' - 'procedureCode' - 'checkCode' - 'normalizerCode' - 'parameters' AS run_configuration
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    /** Регистрирует набор под постоянным идентификатором и обновляет метаданные манифеста. */
    public void register(Dataset dataset) {
        jdbc.sql("""
                INSERT INTO source_datasets (id, name, source_run_id, application_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
                    application_count = EXCLUDED.application_count
                """).params(dataset.id(), dataset.name(), dataset.sourceRunId(), dataset.applicationCount()).update();
    }

    /** Сохраняет задание и возвращает исходное состояние из той же SQL-команды. */
    public Run enqueue(UUID datasetId, UUID workspace, Snapshot snapshot) {
        return jdbc.sql("""
                INSERT INTO dataset_comparisons (id, dataset_id, status, workspace_id, configuration_snapshot)
                VALUES (?, ?, 'PENDING', ?, CAST(? AS jsonb)) RETURNING
                """ + RUN_COLUMNS).param(UUID.randomUUID()).param(datasetId).param(workspace)
                .param(snapshot == null ? null : json.writeValueAsString(snapshot)).query(this::mapRun).single();
    }

    /** Захватывает одно ожидающее задание атомарно, пропуская строки других работников. */
    public Optional<Run> claim() {
        return jdbc.sql("""
                UPDATE dataset_comparisons SET status = 'RUNNING', started_at = now()
                WHERE id = (
                    SELECT id FROM dataset_comparisons WHERE status = 'PENDING'
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                ) RETURNING
                """ + RUN_COLUMNS).query(this::mapRun).optional();
    }

    /** Читает закрытый снимок для исполнителя очереди. */
    public Optional<Snapshot> snapshot(UUID id) {
        return jdbc.sql("""
                SELECT configuration_snapshot::text FROM dataset_comparisons
                WHERE id = ? AND configuration_snapshot IS NOT NULL
                """).param(id).query(String.class).optional().map(value -> json.readValue(value, Snapshot.class));
    }

    /** Фиксирует наблюдаемый отпечаток входа до выполнения этапов. */
    public void recordInput(UUID id, String fingerprint, String runtime) {
        jdbc.sql("""
                UPDATE dataset_comparisons SET input_sha256 = ?, runtime_sha256 = ?
                WHERE id = ? AND status = 'RUNNING' AND input_sha256 IS NULL
                """).params(fingerprint, runtime, id).update();
    }

    /** Копирует снимок и ожидаемые отпечатки завершённого запуска владельца в новое задание. */
    public Optional<Run> enqueueReplay(UUID original, UUID workspace) {
        return jdbc.sql("""
                INSERT INTO dataset_comparisons
                    (id, dataset_id, status, workspace_id, configuration_snapshot, expected_input_sha256, expected_runtime_sha256)
                SELECT ?, dataset_id, 'PENDING', workspace_id, configuration_snapshot, input_sha256, runtime_sha256
                FROM dataset_comparisons
                WHERE id = ? AND workspace_id = ? AND status = 'COMPLETED'
                    AND configuration_snapshot #>> '{analysis,schemaVersion}' IS NOT NULL
                    AND input_sha256 IS NOT NULL AND runtime_sha256 IS NOT NULL
                RETURNING
                """ + RUN_COLUMNS).params(UUID.randomUUID(), original, workspace).query(this::mapRun).optional();
    }

    /** Читает только параметры зафиксированной конфигурации запуска. */
    public String frozenParameters(UUID id) {
        return jdbc.sql("""
                SELECT COALESCE(configuration_snapshot #>> '{configuration,parameters}', '{}')
                FROM dataset_comparisons WHERE id = ?
                """).param(id).query(String.class).optional().orElse("{}");
    }

    /** Отмечает переход от отдельной нормализации к сравнению. */
    public void startComparison(UUID id) {
        jdbc.sql("""
                UPDATE dataset_comparisons SET comparison_started_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND comparison_started_at IS NULL
                """).param(id).update();
    }

    /** Фиксирует начало проверок отдельной записью до запуска Python. */
    public void startChecks(UUID id) {
        jdbc.sql("""
                UPDATE dataset_comparisons SET checks_started_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND checks_started_at IS NULL
                """).param(id).update();
    }

    /** Фиксирует начало отдельной подготовки фактов для сводной. */
    public void startFacts(UUID id) {
        jdbc.sql("""
                UPDATE dataset_comparisons SET facts_started_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND facts_started_at IS NULL
                """).param(id).update();
    }

    /** Фиксирует начало импорта вне транзакции публикации отчёта. */
    public void startImport(UUID id) {
        jdbc.sql("""
                UPDATE dataset_comparisons SET import_started_at = clock_timestamp()
                WHERE id = ? AND status = 'RUNNING' AND import_started_at IS NULL
                """).param(id).update();
    }

    /** Отмечает запущенную обработку как неуспешную. */
    public void fail(UUID id, String code, String message) {
        jdbc.sql("""
                UPDATE dataset_comparisons SET status = 'FAILED', finished_at = now(),
                    error_code = ?, error_message = ?
                WHERE id = ? AND status = 'RUNNING'
                """).params(code, message, id).update();
    }

    /** Завершает прерванные задания при старте единственного экземпляра сервиса. */
    public void recoverInterrupted() {
        jdbc.sql("""
                UPDATE dataset_comparisons SET status = 'FAILED', finished_at = now(),
                    error_code = 'INTERRUPTED', error_message = 'Обработка прервана перезапуском сервиса'
                WHERE status = 'RUNNING'
                """).update();
    }

    /** Читает запуск вместе со временем и диагностикой. */
    public Optional<Run> find(UUID id) {
        return jdbc.sql("""
                SELECT
                """ + RUN_COLUMNS + """
                FROM dataset_comparisons WHERE id = ?
                """)
                .param(id).query(this::mapRun).optional();
    }

    /** Проверяет доступ к своему запуску или записи, созданной до рабочих областей. */
    public boolean canView(UUID id, UUID workspace) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM dataset_comparisons WHERE id = ?
                    AND (workspace_id IS NULL OR workspace_id = ?))
                """).params(id, workspace).query(Boolean.class).single();
    }

    /** Возвращает историю своей области и ранее опубликованные общие запуски. */
    public List<Run> history(UUID workspace, int size, long offset) {
        return jdbc.sql("""
                SELECT
                """ + RUN_COLUMNS + """
                FROM dataset_comparisons WHERE workspace_id IS NULL OR workspace_id = ?
                ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """).params(workspace, size, offset).query(this::mapRun).list();
    }

    /** Считает доступные запуски для пагинации истории. */
    public long countRuns(UUID workspace) {
        return jdbc.sql("""
                SELECT count(*) FROM dataset_comparisons WHERE workspace_id IS NULL OR workspace_id = ?
                """).param(workspace).query(Long.class).single();
    }

    /** Читает опубликованные счётчики и рассчитывает матрицу по импортированным решениям. */
    public Report report(UUID id) {
        return jdbc.sql("""
                SELECT r.total_applications, r.applications_with_differences, r.applications_with_decision_changes,
                    m.aa, m.ar, m.ra, m.rr, (r.check_report IS NOT NULL OR jsonb_path_exists(
                        r.configuration_snapshot, '$.analysis.checks[*] ? (@.enabled == false)')), r.pivot_available,
                    EXISTS (SELECT 1 FROM dataset_comparison_segments s WHERE s.comparison_id = r.id)
                FROM dataset_comparisons r CROSS JOIN LATERAL (
                    SELECT
                        count(*) FILTER (WHERE decision_before = 'APPROVE' AND decision_after = 'APPROVE') AS aa,
                        count(*) FILTER (WHERE decision_before = 'APPROVE' AND decision_after = 'REJECT') AS ar,
                        count(*) FILTER (WHERE decision_before = 'REJECT' AND decision_after = 'APPROVE') AS ra,
                        count(*) FILTER (WHERE decision_before = 'REJECT' AND decision_after = 'REJECT') AS rr
                    FROM dataset_comparison_applications WHERE comparison_id = r.id
                ) m WHERE r.id = ?
                """).param(id).query((row, index) -> new Report(id, row.getLong(1), row.getLong(2),
                row.getLong(3), new Matrix(row.getLong(4), row.getLong(5), row.getLong(6), row.getLong(7)), row.getBoolean(8), row.getBoolean(9), row.getBoolean(10))).single();
    }

    /** Возвращает отфильтрованные заявки в порядке исходных идентификаторов. */
    public List<Application> applications(UUID id, Boolean diff, Boolean changed, Decision before,
                                          Decision after, int size, long offset) {
        return applicationQuery("SELECT *", id, diff, changed, before, after,
                " ORDER BY application_id LIMIT :size OFFSET :offset")
                .param("size", size).param("offset", offset).query(this::mapApplication).list();
    }

    /** Считает заявки с теми же условиями, что применяются при чтении страницы. */
    public long countApplications(UUID id, Boolean diff, Boolean changed, Decision before, Decision after) {
        return applicationQuery("SELECT count(*)", id, diff, changed, before, after, "")
                .query(Long.class).single();
    }

    /** Находит заявку по идентификатору исходного файла внутри запуска. */
    public Optional<Application> application(UUID id, String applicationId) {
        return jdbc.sql("""
                SELECT * FROM dataset_comparison_applications WHERE comparison_id = ? AND application_id = ?
                """).params(id, applicationId).query(this::mapApplication).optional();
    }

    /** Возвращает отличия в порядке пути и ключа массива. */
    public List<Difference> differences(UUID id, String applicationId) {
        return jdbc.sql("""
                SELECT path, array_key, value_before, value_after, change_type
                FROM dataset_comparison_differences WHERE comparison_id = ? AND application_id = ?
                ORDER BY path, array_key
                """).params(id, applicationId).query((row, index) -> new Difference(row.getString(1),
                row.getString(2), row.getString(3), row.getString(4), row.getString(5))).list();
    }

    /** Связывает необязательные фильтры и явно задаёт типы отсутствующих параметров PostgreSQL. */
    private JdbcClient.StatementSpec applicationQuery(String select, UUID id, Boolean diff, Boolean changed,
                                                       Decision before, Decision after, String suffix) {
        return jdbc.sql(select + """
                 FROM dataset_comparison_applications
                 WHERE comparison_id = :id
                   AND (CAST(:diff AS boolean) IS NULL OR has_diff = :diff)
                   AND (CAST(:changed AS boolean) IS NULL OR decision_changed = :changed)
                   AND (CAST(:before AS text) IS NULL OR decision_before = :before)
                   AND (CAST(:after AS text) IS NULL OR decision_after = :after)
                """ + suffix)
                .param("id", id).param("diff", diff).param("changed", changed)
                .param("before", before == null ? null : before.name())
                .param("after", after == null ? null : after.name());
    }

    /** Преобразует запись задания в публичный статус. */
    private Run mapRun(ResultSet row, int index) throws SQLException {
        String code = row.getString("error_code");
        RunStatus status = RunStatus.valueOf(row.getString("status"));
        Instant created = instant(row.getTimestamp("created_at"));
        Instant started = instant(row.getTimestamp("started_at"));
        Instant finished = instant(row.getTimestamp("finished_at"));
        boolean factsFirst = row.getBoolean("facts_first");
        List<Stage> journal = row.getBoolean("journal_enabled")
                ? journal(status, created, started, instant(row.getTimestamp("comparison_started_at")),
                        instant(row.getTimestamp("checks_started_at")), instant(row.getTimestamp("facts_started_at")),
                        instant(row.getTimestamp("import_started_at")),
                        finished, row.getBoolean("normalizer_enabled"), row.getBoolean("facts_enabled"), factsFirst, "FACTS_RESULT_INVALID".equals(code))
                : null;
        String snapshot = row.getString("run_configuration");
        Configuration configuration = snapshot == null ? null
                : json.readValue(snapshot, Configuration.class);
        return new Run(row.getObject("id", UUID.class), row.getObject("dataset_id", UUID.class),
                status, created, started, finished,
                code == null ? null : new ApiError(code, row.getString("error_message"), null), journal, configuration,
                new RunInput(row.getString("input_source_id"), row.getString("input_sha256"),
                        row.getObject("configuration_version", Integer.class), row.getString("expected_input_sha256"),
                        row.getString("runtime_sha256"), row.getString("expected_runtime_sha256")));
    }

    /** Восстанавливает последовательность этапов из одного снимка строки запуска. */
    private List<Stage> journal(RunStatus status, Instant created, Instant started, Instant comparing, Instant checks,
                                Instant facts, Instant importing, Instant finished, boolean normalizer, boolean customFacts, boolean factsFirst,
                                boolean invalidFacts) {
        boolean failed = status == RunStatus.FAILED;
        boolean completed = status == RunStatus.COMPLETED;
        Stage queue = new Stage(StageName.QUEUE, started == null ? StageStatus.PENDING : StageStatus.COMPLETED,
                created, started);
        Instant afterChecks = !factsFirst && customFacts ? facts : importing;
        Instant afterFacts = factsFirst && checks != null ? checks : importing;
        Instant afterComparison = factsFirst && facts != null ? facts : checks == null ? afterChecks : checks;
        Stage comparison = stage(StageName.COMPARISON, normalizer ? comparing : started,
                afterComparison, finished, failed);
        Stage validation = checks == null && afterChecks != null
                ? new Stage(StageName.CHECKS, StageStatus.SKIPPED, null, null)
                : stage(StageName.CHECKS, checks, afterChecks, finished, failed);
        Stage publication = invalidFacts
                ? new Stage(StageName.IMPORT, StageStatus.SKIPPED, null, null)
                : stage(StageName.IMPORT, importing, completed ? finished : null, finished, failed);
        var stages = new ArrayList<Stage>(List.of(queue));
        if (normalizer) {
            stages.add(stage(StageName.NORMALIZATION, started, comparing, finished, failed));
        }
        stages.add(comparison);
        Stage preparation = invalidFacts
                ? new Stage(StageName.FACTS, StageStatus.FAILED, facts, finished)
                : stage(StageName.FACTS, facts, afterFacts, finished, failed);
        if (customFacts && factsFirst) {
            stages.add(preparation);
        }
        stages.add(validation);
        if (customFacts && !factsFirst) {
            stages.add(preparation);
        }
        stages.add(publication);
        return List.copyOf(stages);
    }

    /** Выводит состояние этапа по его старту, переходу к следующему этапу и исходу запуска. */
    private Stage stage(StageName name, Instant started, Instant next, Instant finished, boolean failed) {
        if (started == null) {
            return new Stage(name, failed ? StageStatus.SKIPPED : StageStatus.PENDING, null, null);
        }
        if (next != null) {
            return new Stage(name, StageStatus.COMPLETED, started, next);
        }
        return failed ? new Stage(name, StageStatus.FAILED, started, finished)
                : new Stage(name, StageStatus.RUNNING, started, null);
    }

    /** Читает бизнес-решения и признаки изменений из строки отчёта. */
    private Application mapApplication(ResultSet row, int index) throws SQLException {
        return new Application(row.getString("application_id"), Decision.valueOf(row.getString("decision_before")),
                Decision.valueOf(row.getString("decision_after")), row.getBoolean("has_diff"),
                row.getBoolean("decision_changed"));
    }

    /** Сохраняет отсутствие времени для ещё не наступившего этапа. */
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
    /** Возвращает выключенные позиции плана из неизменяемого снимка без исходников. */
    public String skippedChecks(UUID id) {
        return jdbc.sql("""
                SELECT COALESCE(jsonb_agg(jsonb_build_object(
                    'id', entry ->> 'id',
                    'label', COALESCE(entry #>> '{rule,label}', entry #>> '{script,file}'),
                    'status', 'SKIPPED') ORDER BY position), '[]'::jsonb)::text
                FROM dataset_comparisons c
                CROSS JOIN LATERAL jsonb_array_elements(
                    COALESCE(c.configuration_snapshot #> '{analysis,checks}', '[]'::jsonb))
                    WITH ORDINALITY AS plan(entry, position)
                WHERE c.id = ? AND entry -> 'enabled' = 'false'::jsonb
                """).param(id).query(String.class).single();
    }

    /** Читает опубликованный JSON отчёта, если запуск включал проверки. */
    public String checkReport(UUID id) {
        return jdbc.sql("""
                SELECT check_report::text FROM dataset_comparisons WHERE id = ? AND check_report IS NOT NULL
                """).param(id).query(String.class).optional().orElse(null);
    }
}

package com.kratti.repository;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Загружает CSV в PostgreSQL и переносит проверенные результаты в таблицы отчёта. */
@Repository
@RequiredArgsConstructor
public class DatasetComparisonImportRepository {
    private final JdbcTemplate jdbc;

    /** Отличает ошибку контракта фактов от сбоя записи данных отчёта. */
    public static class InvalidPivotMembershipException extends RuntimeException {
        /** Сохраняет техническую диагностику для журнала сервера. */
        public InvalidPivotMembershipException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Массово загружает обе таблицы и проверяет, что признаки отличий согласованы. */
    public void importRows(UUID id, Path output) {
        jdbc.execute("""
                CREATE TEMP TABLE source_applications_csv (
                    app_id text NOT NULL, decision_before text NOT NULL, decision_after text NOT NULL,
                    has_diff smallint NOT NULL CHECK (has_diff IN (0, 1)),
                    decision_changed smallint NOT NULL CHECK (decision_changed IN (0, 1))
                ) ON COMMIT DROP
                """);
        copy(output.resolve("applications.csv"), "APP_ID;DECISION_BEFORE;DECISION_AFTER;HAS_DIFF;DECISION_CHANGED",
                "COPY source_applications_csv FROM STDIN WITH (FORMAT csv, DELIMITER ';', ENCODING 'UTF8')");
        jdbc.update("""
                INSERT INTO dataset_comparison_applications
                SELECT ?, app_id, decision_before, decision_after, has_diff = 1, decision_changed = 1
                FROM source_applications_csv
                """, id);

        jdbc.execute("""
                CREATE TEMP TABLE source_comparison_csv (
                    app_id text NOT NULL, prefix text, class text, suffix text, array_key text NOT NULL,
                    field_key text, path text NOT NULL, value_before text NOT NULL, value_after text NOT NULL,
                    adjusted_before text, adjusted_after text, diff_flag smallint NOT NULL,
                    change_type text NOT NULL,
                    CHECK ((diff_flag = 0 AND change_type = 'SAME')
                        OR (diff_flag = 1 AND change_type IN ('VALUE_CHANGED', 'ATTRIBUTE_NEW', 'ATTRIBUTE_LOST')))
                ) ON COMMIT DROP
                """);
        copy(output.resolve("comparison.csv"),
                "APP_ID;PREFIX;CLASS;SUFFIX;ARRAY_KEY;KEY;PATH;VALUE_BEFORE;VALUE_AFTER;"
                        + "ADJUSTED_BEFORE;ADJUSTED_AFTER;DIFF_FLAG;CHANGE_TYPE",
                """
                COPY source_comparison_csv FROM STDIN WITH (FORMAT csv, DELIMITER ';', ENCODING 'UTF8',
                    FORCE_NOT_NULL (array_key, value_before, value_after))
                """);
        jdbc.update("""
                INSERT INTO dataset_comparison_attributes
                SELECT ?, app_id, path, array_key, coalesce(prefix, ''), coalesce(class, ''),
                    coalesce(suffix, ''), coalesce(field_key, ''),
                    CASE WHEN change_type <> 'ATTRIBUTE_NEW' THEN value_before END,
                    CASE WHEN change_type <> 'ATTRIBUTE_LOST' THEN value_after END,
                    CASE WHEN change_type <> 'ATTRIBUTE_NEW' THEN coalesce(adjusted_before, '') END,
                    CASE WHEN change_type <> 'ATTRIBUTE_LOST' THEN coalesce(adjusted_after, '') END,
                    change_type
                FROM source_comparison_csv
                """, id);
        jdbc.update("""
                INSERT INTO dataset_comparison_differences
                SELECT comparison_id, application_id, path, array_key, value_before, value_after, change_type
                FROM dataset_comparison_attributes WHERE comparison_id = ? AND change_type <> 'SAME'
                """, id);

        Path segments = output.resolve("segments.csv");
        if (Files.exists(segments)) {
            importSegments(id, segments);
        }
        Boolean invalid = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM dataset_comparison_applications a
                    WHERE a.comparison_id = ? AND a.has_diff <> EXISTS (
                        SELECT 1 FROM dataset_comparison_differences d
                        WHERE d.comparison_id = a.comparison_id AND d.application_id = a.application_id
                    )
                )
                """, Boolean.class, id);
        if (Boolean.TRUE.equals(invalid)) {
            throw new IllegalStateException("Признаки отличий не совпадают с импортированными полями");
        }
    }

    /** Сохраняет состав сводной и проверяет, что каждое вхождение ссылается на существующий срез. */
    public void importPivotMembership(UUID id, Path file) {
        jdbc.execute("""
                CREATE TEMP TABLE source_pivot_membership (
                    app_id text COLLATE "C" NOT NULL, path text COLLATE "C" NOT NULL,
                    array_key text COLLATE "C" NOT NULL, source text NOT NULL,
                    PRIMARY KEY (app_id, path, array_key, source)
                ) ON COMMIT DROP
                """);
        try {
            copy(file, "APP_ID;PATH;ARRAY_KEY;SOURCE", """
                    COPY source_pivot_membership FROM STDIN WITH (FORMAT csv, DELIMITER ';', ENCODING 'UTF8',
                        FORCE_NOT_NULL (array_key))
                    """);
        } catch (UncheckedIOException error) {
            throw new InvalidPivotMembershipException("Не удалось прочитать CSV фактов", error);
        } catch (DataAccessException error) {
            if (error.getMostSpecificCause() instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("22") || state.startsWith("23") || state.equals("54000"))) {
                    throw new InvalidPivotMembershipException("Нарушен контракт CSV фактов", error);
                }
            }
            throw error;
        }
        int inserted = jdbc.update("""
                INSERT INTO dataset_pivot_membership
                SELECT a.comparison_id, a.application_id, a.path, a.array_key, a.source
                FROM source_pivot_membership f JOIN dataset_comparison_slices a
                    ON a.comparison_id = ? AND a.application_id = f.app_id
                    AND a.path = f.path AND a.array_key = f.array_key AND a.source = f.source
                """, id);
        Long supplied = jdbc.queryForObject("SELECT count(*) FROM source_pivot_membership", Long.class);
        if (supplied == null || inserted != supplied) {
            throw new InvalidPivotMembershipException("Подготовка фактов сослалась на отсутствующий срез сравнения", null);
        }
        jdbc.update("UPDATE dataset_comparisons SET custom_pivot_membership = true WHERE id = ?", id);
    }

    /** Импортирует один разрез на каждую заявку и проверяет совпадение состава. */
    private void importSegments(UUID id, Path file) {
        jdbc.execute("""
                CREATE TEMP TABLE source_segments_csv (
                    app_id text PRIMARY KEY, decision_before text NOT NULL, decision_after text NOT NULL,
                    has_diff smallint NOT NULL CHECK (has_diff IN (0, 1)),
                    decision_changed smallint NOT NULL CHECK (decision_changed IN (0, 1)),
                    products text NOT NULL, partner_types text NOT NULL, routing_methods text NOT NULL,
                    top_offer_before text, top_offer_after text, pd_model_before text, pd_model_after text,
                    cutoff_before text, cutoff_after text, reject_reason_before text, reject_reason_after text,
                    score_okb_before text, score_okb_after text
                ) ON COMMIT DROP
                """);
        copy(file, "APP_ID;DECISION_BEFORE;DECISION_AFTER;HAS_DIFF;DECISION_CHANGED;PRODUCTS;PARTNER_TYPE;"
                        + "ROUTING_METHOD;TOP_OFFER_BEFORE;TOP_OFFER_AFTER;PD_MODEL_BEFORE;PD_MODEL_AFTER;"
                        + "CUTOFF_BEFORE;CUTOFF_AFTER;REJECT_REASON_BEFORE;REJECT_REASON_AFTER;"
                        + "SCORE_OKB_BEFORE;SCORE_OKB_AFTER",
                """
                COPY source_segments_csv FROM STDIN WITH (FORMAT csv, DELIMITER ';', ENCODING 'UTF8',
                    FORCE_NOT_NULL (products, partner_types, routing_methods))
                """);
        int inserted = jdbc.update("""
                INSERT INTO dataset_comparison_segments
                SELECT ?, s.app_id, s.products, s.partner_types, s.routing_methods, s.top_offer_before, s.top_offer_after,
                    s.pd_model_before, s.pd_model_after, s.cutoff_before, s.cutoff_after, s.reject_reason_before,
                    s.reject_reason_after, s.score_okb_before, s.score_okb_after
                FROM source_segments_csv s JOIN dataset_comparison_applications a
                    ON a.comparison_id = ? AND a.application_id = s.app_id
                        AND a.decision_before = s.decision_before AND a.decision_after = s.decision_after
                        AND a.has_diff = (s.has_diff = 1) AND a.decision_changed = (s.decision_changed = 1)
                """, id, id);
        Long expected = jdbc.queryForObject("""
                SELECT count(*) FROM dataset_comparison_applications WHERE comparison_id = ?
                """, Long.class, id);
        Long supplied = jdbc.queryForObject("SELECT count(*) FROM source_segments_csv", Long.class);
        if (expected == null || inserted != expected || !expected.equals(supplied)) {
            throw new IllegalStateException("Слой разрезов должен содержать все заявки запуска");
        }
    }

    /** Публикует итог, только если данные заявок совпадают со счётчиками summary.json. */
    public void complete(UUID id, long total, long differences, long decisionChanges, String checkReport) {
        int updated = jdbc.update("""
                UPDATE dataset_comparisons r SET status = 'COMPLETED', finished_at = clock_timestamp(),
                    total_applications = counts.total, applications_with_differences = counts.differences,
                    applications_with_decision_changes = counts.decision_changes, check_report = CAST(? AS jsonb), pivot_available = true
                FROM (
                    SELECT count(*) AS total, count(*) FILTER (WHERE has_diff) AS differences,
                        count(*) FILTER (WHERE decision_changed) AS decision_changes
                    FROM dataset_comparison_applications WHERE comparison_id = ?
                ) counts
                WHERE r.id = ? AND r.status = 'RUNNING'
                    AND counts.total = ? AND counts.differences = ? AND counts.decision_changes = ?
                """, checkReport, id, id, total, differences, decisionChanges);
        if (updated != 1) {
            throw new IllegalStateException("Состояние запуска или счётчики не совпадают с результатом Python");
        }
    }

    /** Проверяет заголовок и потоково передаёт CSV драйверу с учётом многострочных полей. */
    private void copy(Path file, String header, String sql) {
        jdbc.execute((ConnectionCallback<Long>) connection -> {
            try (var stream = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] expected = header.getBytes(StandardCharsets.US_ASCII);
                byte[] actual = stream.readNBytes(expected.length);
                int lineEnd = stream.read();
                if (lineEnd == '\r') {
                    lineEnd = stream.read();
                }
                if (!Arrays.equals(expected, actual) || lineEnd != '\n') {
                    throw new IOException("Неожиданный заголовок CSV");
                }
                return connection.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, stream);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
    }
}

package com.kratti.repository;

import com.kratti.dto.response.DatasetComparisonViews.StageName;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Хранит неизменяемые снимки файлов, подготовленных для этапов запуска. */
@Repository
@RequiredArgsConstructor
public class DatasetStageSourceRepository {
    private final JdbcClient jdbc;

    /** Сохраняет все файлы этапа одной операцией до исполнения. */
    public void insert(UUID id, StageName stage, String sources) {
        jdbc.sql("""
                INSERT INTO dataset_comparison_stage_sources (comparison_id, stage, sources)
                VALUES (?, ?, CAST(? AS jsonb))
                """).params(id, stage.name(), sources).update();
    }

    /** Читает сохранённый снимок конкретного этапа. */
    public Optional<String> find(UUID id, StageName stage) {
        return jdbc.sql("""
                SELECT sources::text FROM dataset_comparison_stage_sources WHERE comparison_id = ? AND stage = ?
                """).params(id, stage.name()).query(String.class).optional();
    }
}

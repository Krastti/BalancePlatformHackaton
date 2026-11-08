package com.kratti.repository;

import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.DatasetConfigurationViews.Configuration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Хранит конфигурации отдельно для каждой рабочей области. */
@Repository
@RequiredArgsConstructor
public class DatasetConfigurationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    /** Возвращает конфигурации рабочей области от новых к старым. */
    public List<Configuration> list(UUID workspace) {
        return jdbc.sql("""
                SELECT * FROM dataset_comparison_configurations WHERE workspace_id = ?
                ORDER BY updated_at DESC, id
                """).param(workspace).query(this::map).list();
    }

    /** Читает обе формы всех конфигураций рабочей области в порядке обновления. */
    public List<Stored> listStored(UUID workspace) {
        return jdbc.sql("""
                SELECT * FROM dataset_comparison_configurations WHERE workspace_id = ?
                ORDER BY updated_at DESC, id
                """).param(workspace).query(this::mapStored).list();
    }

    /** Содержит обе формы настроек из одной версии строки базы. */
    public record Stored(Configuration legacy, AnalysisConfiguration analysis) { }

    /** Читает обе формы конфигурации только в области её владельца. */
    public Optional<Stored> find(UUID workspace, UUID id) {
        return jdbc.sql("""
                SELECT * FROM dataset_comparison_configurations WHERE workspace_id = ? AND id = ?
                """).params(workspace, id).query(this::mapStored).optional();
    }

    /** Блокирует строку при редактировании из старой формы, чтобы сохранить текущий порядок. */
    public Optional<Stored> lock(UUID workspace, UUID id) {
        return jdbc.sql("""
                SELECT * FROM dataset_comparison_configurations WHERE workspace_id = ? AND id = ?
                FOR UPDATE
                """).params(workspace, id).query(this::mapStored).optional();
    }

    /** Вставляет новую конфигурацию в рабочую область. */
    public Configuration insert(UUID workspace, Configuration configuration, AnalysisConfiguration analysis) {
        return jdbc.sql("""
                INSERT INTO dataset_comparison_configurations (id, workspace_id, name, dataset_id, checks, procedure_name, procedure_code, check_code, parameters, normalizer_name, normalizer_code, facts_procedure_name, facts_code, analysis_configuration)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS jsonb)) RETURNING *
                """).param(configuration.id()).param(workspace).param(configuration.name())
                .param(configuration.datasetId()).param(json.writeValueAsString(configuration.checks()))
                .param(configuration.procedure()).param(configuration.procedureCode()).param(configuration.checkCode()).param(json.writeValueAsString(configuration.parameters())).param(configuration.normalizer())
                .param(configuration.normalizerCode()).param(configuration.factsProcedure()).param(configuration.factsCode()).param(json.writeValueAsString(analysis)).query(this::map).single();
    }

    /** Обновляет только свою конфигурацию и возвращает сохранённые данные. */
    public Optional<Configuration> update(UUID workspace, Configuration configuration, AnalysisConfiguration analysis) {
        return jdbc.sql("""
                UPDATE dataset_comparison_configurations
                SET name = ?, dataset_id = ?, checks = CAST(? AS jsonb), procedure_name = ?, procedure_code = ?, check_code = ?, parameters = CAST(? AS jsonb), normalizer_name = ?, normalizer_code = ?, facts_procedure_name = ?, facts_code = ?, analysis_configuration = CAST(? AS jsonb), updated_at = clock_timestamp()
                WHERE workspace_id = ? AND id = ? RETURNING *
                """).param(configuration.name()).param(configuration.datasetId())
                .param(json.writeValueAsString(configuration.checks())).param(configuration.procedure())
                .param(configuration.procedureCode()).param(configuration.checkCode()).param(json.writeValueAsString(configuration.parameters())).param(configuration.normalizer())
                .param(configuration.normalizerCode()).param(configuration.factsProcedure()).param(configuration.factsCode()).param(json.writeValueAsString(analysis)).param(workspace).param(configuration.id()).query(this::map).optional();
    }

    /** Удаляет только что созданную временную конфигурацию в рамках транзакции запуска. */
    public void delete(UUID workspace, UUID id) {
        jdbc.sql("DELETE FROM dataset_comparison_configurations WHERE workspace_id = ? AND id = ?")
                .params(workspace, id).update();
    }

    /** Читает документ v2 из той же строки, что и настройки старого исполнителя. */
    private Stored mapStored(ResultSet row, int index) throws SQLException {
        String document = row.getString("analysis_configuration");
        return new Stored(map(row, index), document == null ? null : json.readValue(document, AnalysisConfiguration.class));
    }

    /** Преобразует строку PostgreSQL в публичное представление. */
    private Configuration map(ResultSet row, int index) throws SQLException {
        return new Configuration(row.getObject("id", UUID.class), row.getString("name"),
                row.getObject("dataset_id", UUID.class), json.readValue(row.getString("checks"), new TypeReference<List<String>>() { }),
                row.getString("procedure_name"), row.getString("procedure_code"), row.getString("check_code"), row.getString("parameters") == null ? null : json.readTree(row.getString("parameters")), row.getString("normalizer_name"), row.getString("normalizer_code"), row.getString("facts_procedure_name"), row.getString("facts_code"));
    }
}

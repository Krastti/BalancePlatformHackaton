package com.kratti.service;

import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import com.kratti.repository.DatasetComparisonImportRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Импортирует файлы одного запуска и публикует отчёт в общей транзакции. */
@Service
@RequiredArgsConstructor
public class DatasetComparisonImportService {
    private final DatasetComparisonImportRepository repository;
    private final ObjectMapper json;

    /** Сверяет метаданные и откатывает весь импорт при любой ошибке чтения или записи. */
    @Transactional(rollbackFor = IOException.class)
    public void publish(UUID id, Dataset dataset, Path output, Path pivotMembership) throws IOException {
        JsonNode summary = json.readTree(Files.readString(output.resolve("summary.json")));
        long total = count(summary, "total_applications");
        long differences = count(summary, "applications_with_differences");
        long decisionChanges = count(summary, "applications_with_decision_changes");
        if (!dataset.sourceRunId().equals(summary.path("run_id").asString()) || total != dataset.applicationCount()) {
            throw new IllegalArgumentException("Итог Python не соответствует зарегистрированному набору");
        }
        repository.importRows(id, output);
        if (pivotMembership != null) {
            repository.importPivotMembership(id, pivotMembership);
        }
        Path checks = output.resolve("checks.json");
        String checkReport = Files.exists(checks) ? Files.readString(checks) : null;
        if (checkReport != null) {
            JsonNode report = json.readTree(checkReport);
            if (!report.path("checks").isArray() || report.path("checks").isEmpty() || !report.path("rows").isArray()) {
                throw new IllegalArgumentException("Некорректный отчёт проверок");
            }
        }
        repository.complete(id, total, differences, decisionChanges, checkReport);
    }

    /** Читает обязательный неотрицательный целочисленный счётчик. */
    private long count(JsonNode summary, String field) {
        JsonNode value = summary.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("Некорректный счётчик " + field);
        }
        return value.asLong();
    }
}

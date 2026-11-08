package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratti.enums.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Представления каталога и отчётов по готовым ответам СПР. */
public final class DatasetComparisonViews {
    /** Запрещает создание контейнера типов. */
    private DatasetComparisonViews() {
    }

    /** Зарегистрированный набор с числом пар из манифеста. */
    public record Dataset(UUID id, String name, String sourceRunId, long applicationCount) { }

    /** Показывает пару решений без выдачи исходных ответов или серверных путей. */
    public record DatasetPreview(String applicationId, String beforeLabel, String afterLabel,
                                 String decisionBefore, String decisionAfter) { }

    /** Запуск со временем обработки и безопасной диагностикой в случае ошибки. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Run(UUID id, UUID datasetId, RunStatus status, Instant createdAt,
                      Instant startedAt, Instant finishedAt, ApiError error, List<Stage> journal,
                      DatasetConfigurationViews.Configuration configuration, RunInput input) { }

    /** Описывает зафиксированный вход и версию настроек без серверного пути. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunInput(String sourceRunId, String sha256, Integer schemaVersion, String expectedSha256, String runtimeSha256, String expectedRuntimeSha256) { }

    /** Этап запуска с фактическим временем начала, завершения и текущим исходом. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage(StageName name, StageStatus status, Instant startedAt, Instant finishedAt) { }

    /** Последовательность действий фонового запуска. */
    public enum StageName {
        QUEUE, NORMALIZATION, COMPARISON, FACTS, CHECKS, IMPORT
    }

    /** Состояние отдельного этапа, включая пропущенные проверки. */
    public enum StageStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    /** Метаданные выборки после применения фильтров. */
    public record PageMetadata(int page, int size, long totalElements, long totalPages) { }

    /** Элементы страницы и метаданные пагинации. */
    public record Page<T>(List<T> items, PageMetadata page) { }

    /** Решение из готового ответа СПР. */
    public enum Decision {
        APPROVE, REJECT
    }

    /** Решения обеих сторон и признаки отличий по одной заявке. */
    public record Application(String applicationId, Decision decisionBefore, Decision decisionAfter,
                              boolean hasDiff, boolean decisionChanged) { }

    /** Изменившееся поле с ключом массива и значениями обеих сторон. */
    public record Difference(String path, String arrayKey, String valueBefore, String valueAfter,
                             String changeType) { }

    /** Решения и изменения полей для выбранной заявки. */
    public record Detail(String applicationId, Decision decisionBefore, Decision decisionAfter,
                         boolean hasDiff, boolean decisionChanged, List<Difference> differences) { }

    /** Распределение заявок по четырём комбинациям решений. */
    public record Matrix(long approveToApprove, long approveToReject, long rejectToApprove,
                         long rejectToReject) { }

    /** Сводка полностью импортированного запуска. */
    public record Report(UUID comparisonId, long totalApplications, long applicationsWithDifferences,
                         long applicationsWithDecisionChanges, Matrix transitionMatrix,
                         boolean checksAvailable, boolean pivotAvailable, boolean segmentsAvailable) { }
}

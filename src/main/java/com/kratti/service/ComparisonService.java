package com.kratti.service;

import com.kratti.dto.request.CreateComparisonRequest;
import com.kratti.dto.response.ErrorDetails;
import com.kratti.dto.response.PageResponse;
import com.kratti.dto.response.RunView;
import com.kratti.entity.ComparisonRun;
import com.kratti.enums.RunStatus;
import com.kratti.exception.ApiException;
import com.kratti.mapper.ApiMapper;
import com.kratti.repository.ApplicationDatasetRepository;
import com.kratti.repository.ComparisonRunRepository;
import com.kratti.repository.StrategyVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт устойчивую очередь запусков в PostgreSQL и управляет их жизненным циклом. */
@Service
@RequiredArgsConstructor
public class ComparisonService {
    private final ComparisonRunRepository runs;
    private final ApplicationDatasetRepository datasets;
    private final StrategyVersionRepository strategies;
    private final ApiMapper mapper;

    /** Фиксирует входы и создаёт новый ожидающий запуск для каждого запроса. */
    @Transactional
    public RunView create(CreateComparisonRequest request) {
        RequestValidation validation = new RequestValidation();
        validation.required(request.datasetId(), "datasetId");
        validation.required(request.oldVersionId(), "oldVersionId");
        validation.required(request.newVersionId(), "newVersionId");
        validation.rejectIfInvalid();
        if (!datasets.existsById(request.datasetId())) {
            throw new ApiException(404, "DATASET_NOT_FOUND", "Набор заявок не найден");
        }
        if (!strategies.existsById(request.oldVersionId()) || !strategies.existsById(request.newVersionId())) {
            throw new ApiException(404, "STRATEGY_VERSION_NOT_FOUND", "Версия стратегии не найдена");
        }
        ComparisonRun run = ComparisonRun.builder().datasetId(request.datasetId())
                .oldVersionId(request.oldVersionId()).newVersionId(request.newVersionId())
                .executorVersion(ThresholdStrategyExecutor.VERSION).status(RunStatus.PENDING)
                .oldExecutionStatus(RunStatus.PENDING).newExecutionStatus(RunStatus.PENDING).build();
        return mapper.run(runs.saveAndFlush(run));
    }

    /** Возвращает историю запусков со стабильным порядком страниц. */
    @Transactional(readOnly = true)
    public PageResponse<RunView> list(int page, int size) {
        return Pagination.response(runs.findAll(Pagination.request(page, size)).map(mapper::run));
    }

    /** Возвращает параметры и состояние выбранного запуска. */
    @Transactional(readOnly = true)
    public RunView get(UUID id) {
        return mapper.run(requireRun(id));
    }

    /** Находит запуск или возвращает документированный код отсутствующего ресурса. */
    @Transactional(readOnly = true)
    public ComparisonRun requireRun(UUID id) {
        return runs.findById(id).orElseThrow(() -> new ApiException(404, "COMPARISON_NOT_FOUND", "Сравнение не найдено"));
    }

    /** Фиксирует захват ожидающего запуска до начала длительного расчёта. */
    @Transactional
    public Optional<UUID> claimNext() {
        return runs.lockNextPending().map(run -> {
            run.setStatus(RunStatus.RUNNING);
            run.setOldExecutionStatus(RunStatus.RUNNING);
            run.setNewExecutionStatus(RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            return run.getId();
        });
    }

    /** Завершает общую ошибку отдельно от технических ошибок отдельных заявок. */
    @Transactional
    public void fail(UUID id, String code, String message) {
        ComparisonRun run = requireRun(id);
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
            return;
        }
        run.setStatus(RunStatus.FAILED);
        run.setOldExecutionStatus(RunStatus.FAILED);
        run.setNewExecutionStatus(RunStatus.FAILED);
        run.setErrorDetails(new ErrorDetails(code, message));
        run.setFinishedAt(Instant.now());
    }

    /** Завершает оставшиеся RUNNING после перезапуска единственного экземпляра MVP. */
    @Transactional
    public void recoverInterrupted() {
        for (ComparisonRun run : runs.findByStatus(RunStatus.RUNNING)) {
            fail(run.getId(), "EXECUTION_INTERRUPTED", "Расчёт прерван перезапуском сервиса; создайте новое сравнение");
        }
    }
}

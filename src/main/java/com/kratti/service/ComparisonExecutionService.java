package com.kratti.service;

import com.kratti.entity.ApplicationResult;
import com.kratti.entity.ComparisonRun;
import com.kratti.enums.ResultSide;
import com.kratti.enums.ResultStatus;
import com.kratti.enums.RunStatus;
import com.kratti.repository.ApplicationResultRepository;
import com.kratti.repository.ComparisonRunRepository;
import com.kratti.repository.HistoricalApplicationRepository;
import com.kratti.repository.StrategyVersionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Выполняет обе стратегии на одном снимке и атомарно публикует результаты завершённого расчёта. */
@Service
@RequiredArgsConstructor
public class ComparisonExecutionService {
    private final ComparisonRunRepository runs;
    private final HistoricalApplicationRepository applications;
    private final StrategyVersionRepository strategies;
    private final ApplicationResultRepository results;
    private final ThresholdStrategyExecutor executor;

    /** Сохраняет все результаты и финальный статус одной транзакцией. */
    @Transactional
    public void execute(UUID id) {
        ComparisonRun run = runs.findById(id).orElseThrow();
        if (run.getStatus() != RunStatus.RUNNING) {
            return;
        }
        if (!ThresholdStrategyExecutor.VERSION.equals(run.getExecutorVersion())) {
            throw new IllegalStateException("Версия исполнителя недоступна");
        }
        var oldDefinition = strategies.findById(run.getOldVersionId()).orElseThrow().getDefinition();
        var newDefinition = strategies.findById(run.getNewVersionId()).orElseThrow().getDefinition();
        List<ApplicationResult> calculated = new ArrayList<>();
        for (var application : applications.findByDatasetIdOrderByIdAsc(run.getDatasetId())) {
            calculated.add(executor.evaluate(id, application, ResultSide.OLD, oldDefinition));
            calculated.add(executor.evaluate(id, application, ResultSide.NEW, newDefinition));
        }
        results.saveAll(calculated);
        run.setOldExecutionStatus(sideStatus(calculated, ResultSide.OLD));
        run.setNewExecutionStatus(sideStatus(calculated, ResultSide.NEW));
        run.setStatus(run.getOldExecutionStatus() == RunStatus.COMPLETED && run.getNewExecutionStatus() == RunStatus.COMPLETED
                ? RunStatus.COMPLETED : RunStatus.PARTIAL);
        run.setFinishedAt(Instant.now());
    }

    /** Отделяет завершение с ошибками заявок от общего сбоя исполнителя. */
    private RunStatus sideStatus(List<ApplicationResult> calculated, ResultSide side) {
        return calculated.stream().anyMatch(result -> result.getSide() == side && result.getStatus() == ResultStatus.ERROR)
                ? RunStatus.PARTIAL : RunStatus.COMPLETED;
    }
}

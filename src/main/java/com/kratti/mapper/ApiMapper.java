package com.kratti.mapper;

import com.kratti.dto.response.ApplicationView;
import com.kratti.dto.response.DatasetView;
import com.kratti.dto.response.ResultView;
import com.kratti.dto.response.RunView;
import com.kratti.dto.response.StrategyView;
import com.kratti.entity.ApplicationDataset;
import com.kratti.entity.ApplicationResult;
import com.kratti.entity.ComparisonRun;
import com.kratti.entity.HistoricalApplication;
import com.kratti.entity.StrategyVersion;
import org.springframework.stereotype.Component;

/** Преобразует сущности в HTTP-представления без раскрытия состояния JPA. */
@Component
public class ApiMapper {
    /** Представляет сохранённый набор заявок. */
    public DatasetView dataset(ApplicationDataset source) {
        return new DatasetView(source.getId(), source.getName(), source.getCreatedAt());
    }

    /** Представляет неизменяемую версию стратегии. */
    public StrategyView strategy(StrategyVersion source) {
        return new StrategyView(source.getId(), source.getStrategyKey(), source.getVersion(), source.getName(),
                source.getDefinition(), source.getCreatedAt());
    }

    /** Представляет параметры и текущее состояние сравнения. */
    public RunView run(ComparisonRun source) {
        return new RunView(source.getId(), source.getDatasetId(), source.getOldVersionId(), source.getNewVersionId(),
                source.getExecutorVersion(), source.getStatus(), source.getOldExecutionStatus(),
                source.getNewExecutionStatus(), source.getErrorDetails(), source.getCreatedAt(),
                source.getStartedAt(), source.getFinishedAt());
    }

    /** Представляет неизменяемый снимок признаков заявки. */
    public ApplicationView application(HistoricalApplication source) {
        return new ApplicationView(source.getId(), source.getDatasetId(), source.getExternalId(), source.getFeatures());
    }

    /** Представляет результат стороны либо сохраняет отсутствие результата. */
    public ResultView result(ApplicationResult source) {
        if (source == null) {
            return null;
        }
        return new ResultView(source.getId(), source.getComparisonRunId(), source.getApplicationId(), source.getSide(),
                source.getStatus(), source.getDecision(), source.getErrorDetails(), source.getTrace(), source.getCreatedAt());
    }
}

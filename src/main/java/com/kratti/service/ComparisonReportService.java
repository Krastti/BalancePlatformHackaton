package com.kratti.service;

import com.kratti.dto.response.ApplicationDetail;
import com.kratti.dto.response.ApplicationSummary;
import com.kratti.dto.response.PageResponse;
import com.kratti.dto.response.ReportView;
import com.kratti.dto.response.TransitionMatrix;
import com.kratti.entity.ApplicationResult;
import com.kratti.entity.ComparisonRun;
import com.kratti.enums.Decision;
import com.kratti.enums.DecisionTransition;
import com.kratti.entity.HistoricalApplication;
import com.kratti.enums.PairStatus;
import com.kratti.enums.ResultSide;
import com.kratti.enums.ResultStatus;
import com.kratti.enums.RunStatus;
import com.kratti.exception.ApiException;
import com.kratti.mapper.ApiMapper;
import com.kratti.repository.ApplicationResultRepository;
import com.kratti.repository.HistoricalApplicationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Сопоставляет сохранённые стороны и вычисляет отчёт без отдельной таблицы агрегатов. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComparisonReportService {
    private final ComparisonService comparisons;
    private final HistoricalApplicationRepository applications;
    private final ApplicationResultRepository results;
    private final ExplanationService explanations;
    private final ApiMapper mapper;

    /** Вычисляет доли и матрицу на единой когорте успешных пар. */
    public ReportView report(UUID id) {
        ComparisonRun run = finishedRun(id);
        List<ResultPair> pairs = pairs(run);
        Map<DecisionTransition, Long> counts = new EnumMap<>(DecisionTransition.class);
        for (DecisionTransition transition : DecisionTransition.values()) {
            counts.put(transition, 0L);
        }
        long errors = 0;
        long missing = 0;
        for (ResultPair pair : pairs) {
            if (pair.status() == PairStatus.COMPARABLE) {
                counts.merge(pair.transition(), 1L, Long::sum);
            } else if (pair.status() == PairStatus.ERROR) {
                errors++;
            } else {
                missing++;
            }
        }
        long aa = counts.get(DecisionTransition.APPROVE_TO_APPROVE);
        long ad = counts.get(DecisionTransition.APPROVE_TO_DECLINE);
        long da = counts.get(DecisionTransition.DECLINE_TO_APPROVE);
        long dd = counts.get(DecisionTransition.DECLINE_TO_DECLINE);
        long comparable = aa + ad + da + dd;
        BigDecimal oldRate = percent(aa + ad, comparable);
        BigDecimal newRate = percent(aa + da, comparable);
        return new ReportView(id, run.getStatus(), pairs.size(), comparable, errors, missing,
                percent(comparable, pairs.size()), aa + ad, aa + da, oldRate, newRate,
                comparable == 0 ? null : newRate.subtract(oldRate), ad + da, percent(ad + da, comparable),
                new TransitionMatrix(aa, ad, da, dd));
    }

    /** Фильтрует заявки через AND с учётом ошибок и отсутствующих результатов до пагинации. */
    public PageResponse<ApplicationSummary> list(UUID id, int page, int size,
                                                DecisionTransition transition, PairStatus processingStatus) {
        Pagination.request(page, size);
        List<ApplicationSummary> selected = pairs(finishedRun(id)).stream()
                .filter(pair -> transition == null || pair.transition() == transition)
                .filter(pair -> processingStatus == null || pair.status() == processingStatus)
                .map(pair -> new ApplicationSummary(pair.application().getId(), pair.application().getExternalId(),
                        pair.status(), decision(pair.oldResult()), decision(pair.newResult()), pair.transition())).toList();
        return Pagination.slice(selected, page, size);
    }

    /** Возвращает заявку только из набора запуска и объяснение по сохранённым трассам. */
    public ApplicationDetail detail(UUID id, UUID applicationId) {
        ComparisonRun run = finishedRun(id);
        HistoricalApplication application = applications.findByIdAndDatasetId(applicationId, run.getDatasetId())
                .orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Заявка в наборе сравнения не найдена"));
        ResultPair pair = pair(application, results.findByComparisonRunIdAndApplicationId(id, applicationId));
        String explanation = explanations.explain(pair.oldResult(), pair.newResult());
        return new ApplicationDetail(id, mapper.application(application), pair.status(), mapper.result(pair.oldResult()),
                mapper.result(pair.newResult()), pair.transition(), explanation != null, explanation);
    }

    /** Не допускает чтение отчёта и детализации незавершённого расчёта. */
    private ComparisonRun finishedRun(UUID id) {
        ComparisonRun run = comparisons.requireRun(id);
        if (run.getStatus() == RunStatus.PENDING || run.getStatus() == RunStatus.RUNNING) {
            throw new ApiException(409, "COMPARISON_NOT_FINISHED", "Расчёт ещё не завершён");
        }
        return run;
    }

    /** Формирует пары для всех заявок набора, включая полностью отсутствующие результаты. */
    private List<ResultPair> pairs(ComparisonRun run) {
        Map<UUID, List<ApplicationResult>> byApplication = results.findByComparisonRunId(run.getId()).stream()
                .collect(Collectors.groupingBy(ApplicationResult::getApplicationId));
        return applications.findByDatasetIdOrderByIdAsc(run.getDatasetId()).stream()
                .map(application -> pair(application, byApplication.getOrDefault(application.getId(), List.of()))).toList();
    }

    /** Классифицирует пару, отдавая технической ошибке приоритет над отсутствием стороны. */
    private ResultPair pair(HistoricalApplication application, List<ApplicationResult> values) {
        ApplicationResult oldResult = values.stream().filter(result -> result.getSide() == ResultSide.OLD).findFirst().orElse(null);
        ApplicationResult newResult = values.stream().filter(result -> result.getSide() == ResultSide.NEW).findFirst().orElse(null);
        PairStatus status;
        if (values.stream().anyMatch(result -> result.getStatus() == ResultStatus.ERROR)) {
            status = PairStatus.ERROR;
        } else if (oldResult == null || newResult == null) {
            status = PairStatus.MISSING;
        } else {
            status = PairStatus.COMPARABLE;
        }
        DecisionTransition transition = status == PairStatus.COMPARABLE
                ? DecisionTransition.valueOf(oldResult.getDecision().name() + "_TO_" + newResult.getDecision().name()) : null;
        return new ResultPair(application, oldResult, newResult, status, transition);
    }

    /** Возвращает решение только успешно рассчитанной стороны. */
    private Decision decision(ApplicationResult result) {
        return result != null && result.getStatus() == ResultStatus.SUCCEEDED ? result.getDecision() : null;
    }

    /** Возвращает процент с шестью знаками либо null при пустом знаменателе. */
    private BigDecimal percent(long count, long total) {
        return total == 0 ? null : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /** Внутренняя пара результатов одной заявки с вычисленным переходом. */
    private record ResultPair(HistoricalApplication application, ApplicationResult oldResult,
                              ApplicationResult newResult, PairStatus status, DecisionTransition transition) { }
}

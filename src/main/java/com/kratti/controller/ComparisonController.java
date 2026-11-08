package com.kratti.controller;

import com.kratti.dto.response.ApplicationDetail;
import com.kratti.dto.response.ApplicationSummary;
import com.kratti.dto.request.CreateComparisonRequest;
import com.kratti.dto.response.PageResponse;
import com.kratti.dto.response.ReportView;
import com.kratti.dto.response.RunView;
import com.kratti.enums.DecisionTransition;
import com.kratti.enums.PairStatus;
import com.kratti.exception.ApiException;
import com.kratti.service.ComparisonReportService;
import com.kratti.service.ComparisonService;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP-операции фонового сравнения, чтения отчёта и разбора отдельных заявок. */
@RestController
@RequestMapping("/api/legacy/comparisons")
@RequiredArgsConstructor
public class ComparisonController {
    private final ComparisonService comparisons;
    private final ComparisonReportService reports;

    /** Принимает запуск без ожидания расчёта и возвращает Location созданного ресурса. */
    @PostMapping
    public ResponseEntity<RunView> createComparison(@RequestBody CreateComparisonRequest request) {
        RunView run = comparisons.create(request);
        return ResponseEntity.accepted().location(URI.create("/api/legacy/comparisons/" + run.id())).body(run);
    }

    /** Возвращает историю созданных сравнений. */
    @GetMapping
    public PageResponse<RunView> listComparisons(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return comparisons.list(page, size);
    }

    /** Возвращает состояние запуска по UUID. */
    @GetMapping("/{id}")
    public RunView getComparison(@PathVariable UUID id) {
        return comparisons.get(id);
    }

    /** Вычисляет итоговый отчёт завершённого запуска. */
    @GetMapping("/{id}/report")
    public ReportView getComparisonReport(@PathVariable UUID id) {
        return reports.report(id);
    }

    /** Возвращает страницу заявок с совместным применением фильтров. */
    @GetMapping("/{id}/applications")
    public PageResponse<ApplicationSummary> listComparisonApplications(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String transition,
            @RequestParam(required = false) String processingStatus) {
        return reports.list(id, page, size, parseFilter(transition, DecisionTransition.class),
                parseFilter(processingStatus, PairStatus.class));
    }

    /** Возвращает признаки, обе трассы и проверяемое объяснение конкретной заявки. */
    @GetMapping("/{id}/applications/{applicationId}")
    public ApplicationDetail getComparisonApplication(@PathVariable UUID id, @PathVariable UUID applicationId) {
        return reports.detail(id, applicationId);
    }

    /** Отклоняет неизвестные и пустые значения фильтров вместо отключения фильтрации. */
    private <T extends Enum<T>> T parseFilter(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new ApiException(400, "BAD_REQUEST", "Неизвестное значение фильтра " + type.getSimpleName());
        }
    }
}

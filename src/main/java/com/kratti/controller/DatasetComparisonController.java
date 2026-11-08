package com.kratti.controller;

import com.kratti.config.ComparisonWorkspaceConfig;
import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.AnalysisConfiguration.NormalizationRule;
import com.kratti.dto.response.AnalysisConfiguration.TablePreview;
import com.kratti.dto.response.ApiError;
import com.kratti.dto.response.DatasetComparisonViews.Application;
import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import com.kratti.dto.response.DatasetComparisonViews.DatasetPreview;
import com.kratti.dto.response.DatasetComparisonViews.Decision;
import com.kratti.dto.response.DatasetComparisonViews.Detail;
import com.kratti.dto.response.DatasetComparisonViews.Page;
import com.kratti.dto.response.DatasetComparisonViews.Report;
import com.kratti.dto.response.DatasetComparisonViews.Run;
import com.kratti.dto.response.DatasetComparisonViews.StageName;
import com.kratti.exception.ApiException;
import com.kratti.service.DatasetComparisonService;
import com.kratti.service.DatasetStageSourceService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Реализует HTTP-контракт сравнения готовых ответов СПР. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DatasetComparisonController {
    private final DatasetComparisonService comparisons;
    private final DatasetStageSourceService sources;
    private final ObjectMapper json;

    /** Возвращает серверный каталог доступных наборов. */
    @GetMapping("/datasets")
    public Page<Dataset> datasets(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return comparisons.datasets(page, size);
    }

    /** Показывает ограниченный пример двух срезов из зарегистрированного набора. */
    @GetMapping("/comparisons/datasets/{datasetId}/preview")
    public DatasetPreview preview(@PathVariable UUID datasetId) {
        return comparisons.preview(datasetId);
    }

    /** Показывает результат применения табличных правил черновика к одной паре. */
    @PostMapping("/comparisons/datasets/{datasetId}/normalization-preview")
    public JsonNode normalizationPreview(@PathVariable UUID datasetId, @RequestBody List<NormalizationRule> rules) {
        return comparisons.previewNormalization(datasetId, rules);
    }

    /** Показывает поля и интервалы черновика на нормализованной паре. */
    @PostMapping("/comparisons/datasets/{datasetId}/analysis-preview")
    public JsonNode analysisPreview(@PathVariable UUID datasetId, @RequestBody TablePreview draft) {
        return comparisons.previewTables(datasetId, draft.normalization(), draft.analysisFields());
    }

    /** Инициализирует cookie рабочей области перед первым запуском браузера. */
    @GetMapping("/comparisons/workspace")
    public ResponseEntity<Void> workspace() {
        return ResponseEntity.noContent().build();
    }

    /** Ставит в очередь набор с необязательной сохранённой конфигурацией. */
    @PostMapping("/comparisons")
    public ResponseEntity<Run> create(@RequestBody JsonNode body,
                                      @RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace) {
        JsonNode datasetId = body.path("datasetId");
        JsonNode configuration = body.path("configurationId");
        JsonNode draft = body.path("draft");
        boolean hasConfiguration = !configuration.isMissingNode();
        boolean hasDraft = !draft.isMissingNode();
        if (!body.isObject() || !datasetId.isString()
                || body.size() != 1 + (hasConfiguration ? 1 : 0) + (hasDraft ? 1 : 0)
                || (hasConfiguration && hasDraft)
                || (hasConfiguration && !configuration.isString())
                || (hasDraft && !draft.isObject())) {
            throw new ApiException(400, "BAD_REQUEST", "Передайте набор и сохранённую конфигурацию либо черновик");
        }
        Run run = hasDraft
                ? comparisons.createDraft(uuid(datasetId.asString()), workspace, json.convertValue(draft, AnalysisConfiguration.class))
                : comparisons.create(uuid(datasetId.asString()), workspace,
                        hasConfiguration ? uuid(configuration.asString()) : null);
        return ResponseEntity.accepted().location(URI.create("/api/comparisons/" + run.id())).body(run);
    }

    /** Возвращает историю запусков, начиная с последних. */
    @GetMapping("/comparisons")
    public Page<Run> history(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return comparisons.history(workspace, page, size);
    }

    /** Ставит повтор завершённого запуска в очередь с тем же снимком. */
    @PostMapping("/comparisons/{id}/replay")
    public ResponseEntity<Run> replay(@PathVariable String id,
                                      @RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace) {
        Run run = comparisons.replay(uuid(id), workspace);
        return ResponseEntity.accepted().location(URI.create("/api/comparisons/" + run.id())).body(run);
    }

    /** Возвращает состояние фоновой обработки. */
    @GetMapping("/comparisons/{id}")
    public Run status(@PathVariable String id) {
        return comparisons.status(uuid(id));
    }

    /** Возвращает снимок Python-файлов, подготовленный для указанного этапа. */
    @GetMapping("/comparisons/{id}/journal/{stage}/source")
    public JsonNode stageSource(@PathVariable String id, @PathVariable StageName stage) {
        return sources.source(uuid(id), stage);
    }

    /** Возвращает параметры снимка, доступные владельцу запуска. */
    @GetMapping("/comparisons/{id}/journal/parameters")
    public JsonNode frozenParameters(@PathVariable String id) {
        return comparisons.frozenParameters(uuid(id));
    }

    /** Возвращает счётчики и матрицу завершённого запуска. */
    @GetMapping("/comparisons/{id}/report")
    public Report report(@PathVariable String id) {
        return comparisons.report(uuid(id));
    }

    /** Применяет все переданные фильтры до разбиения на страницы. */
    @GetMapping("/comparisons/{id}/applications")
    public Page<Application> applications(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String hasDiff, @RequestParam(required = false) String decisionChanged,
            @RequestParam(required = false) String decisionBefore, @RequestParam(required = false) String decisionAfter) {
        return comparisons.applications(uuid(id), page, size, bool(hasDiff), bool(decisionChanged),
                decision(decisionBefore), decision(decisionAfter));
    }

    /** Возвращает изменения полей для идентификатора исходной заявки. */
    @GetMapping("/comparisons/{id}/applications/{applicationId}")
    public Detail detail(@PathVariable String id, @PathVariable String applicationId) {
        return comparisons.detail(uuid(id), applicationId);
    }

    /** Преобразует ошибки связывания запроса в ответ 400 по контракту. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badRequest() {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Некорректный JSON или параметр запроса", null));
    }

    /** Проверяет каноническую структуру UUID без неявного дополнения коротких групп. */
    private UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Некорректные группы обрабатываются так же, как неканоническая запись.
        }
        throw new ApiException(400, "BAD_REQUEST", "Некорректный UUID");
    }

    /** Различает опущенный фильтр и неверное логическое значение. */
    private Boolean bool(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ApiException(400, "BAD_REQUEST", "Фильтр должен быть true или false");
        };
    }

    /** Принимает только значения решения из OpenAPI. */
    private Decision decision(String value) {
        try {
            return value == null ? null : Decision.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new ApiException(400, "BAD_REQUEST", "Решение должно быть APPROVE или REJECT");
        }
    }
}

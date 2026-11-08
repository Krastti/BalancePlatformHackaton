package com.kratti.controller;

import com.kratti.config.ComparisonWorkspaceConfig;
import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.DatasetConfigurationViews.Configuration;
import com.kratti.dto.response.DatasetConfigurationViews.Selection;
import com.kratti.dto.response.Violation;
import com.kratti.exception.ApiException;
import com.kratti.mapper.AnalysisConfigurationMapper;
import com.kratti.service.DatasetComparisonService;
import com.kratti.service.DatasetConfigurationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Предоставляет каталог проверок и настройки текущей рабочей области. */
@RestController
@RequestMapping("/api/comparisons/configurations")
@RequiredArgsConstructor
public class DatasetConfigurationController {
    private final DatasetConfigurationService configurations;
    private final DatasetComparisonService comparisons;
    private final AnalysisConfigurationMapper analysisConfigurations;

    /** Открывает настройки текущей рабочей области в модели v2. */
    @GetMapping("/{configurationId}/v2")
    public AnalysisConfiguration readV2(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace,
                                        @PathVariable UUID configurationId) {
        return configurations.getV2(workspace, configurationId);
    }

    /** Возвращает документы текущей рабочей области для выбора в мастере. */
    @GetMapping("/v2")
    public List<AnalysisConfiguration> listV2(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace) {
        return configurations.listV2(workspace);
    }

    /** Создаёт конфигурацию из поддерживаемой формы v2. */
    @PostMapping("/v2")
    public AnalysisConfiguration createV2(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace,
                                          @RequestBody AnalysisConfiguration value) {
        return saveV2(workspace, null, value);
    }

    /** Обновляет настройки v2, принадлежащие текущей рабочей области. */
    @PostMapping("/{configurationId}/v2")
    public AnalysisConfiguration updateV2(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace,
                                          @PathVariable UUID configurationId,
                                          @RequestBody AnalysisConfiguration value) {
        configurations.get(workspace, configurationId);
        return saveV2(workspace, configurationId, value);
    }

    /** Проверяет источник и сохраняет настройки через существующий путь записи. */
    private AnalysisConfiguration saveV2(UUID workspace, UUID id, AnalysisConfiguration value) {
        Selection selection = analysisConfigurations.toLegacy(value);
        comparisons.requireDataset(selection.datasetId());
        try {
            return configurations.saveV2(workspace, id, selection, value);
        } catch (ApiException error) {
            if (error.getViolations() == null) {
                throw error;
            }
            var violations = error.getViolations().stream()
                    .map(item -> new Violation(v2Field(item.field(), selection, value.factsFirst()), item.code(), item.message()))
                    .toList();
            throw new ApiException(error.getStatus(), error.getCode(), error.getMessage(), violations);
        }
    }

    /** Переводит пути общей валидации в поля документа v2. */
    private String v2Field(String field, Selection selection, boolean factsFirst) {
        return switch (field) {
            case "datasetId" -> "source.datasetId";
            case "parameters" -> "legacyParameters";
            case "normalizer" -> "stages[0].script.file";
            case "normalizerCode" -> "stages[0].script.code";
            case "procedure" -> "stages[1].script.file";
            case "procedureCode" -> "stages[1].script.code";
            case "factsProcedure" -> (factsFirst ? "stages[2]" : "stages[3]") + ".script.file";
            case "factsCode" -> (factsFirst ? "stages[2]" : "stages[3]") + ".script.code";
            case "checkCode" -> "checks[" + selection.checks().size() + "].script.code";
            default -> field;
        };
    }

    /** Возвращает список сохранённых настроек текущего браузера. */
    @GetMapping
    public List<Configuration> list(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace) {
        return configurations.list(workspace);
    }

    /** Возвращает текст выбранного файла для копирования в редактор. */
    @GetMapping("/scripts/source")
    public JsonNode copyScript(@RequestParam String kind, @RequestParam String name) {
        return configurations.copyScript(kind, name);
    }

    /** Проверяет синтаксис кода из редактора, не исполняя его. */
    @PostMapping("/scripts/validate")
    public JsonNode validateScript(@RequestBody JsonNode body) {
        return configurations.validateScript(body.path("code").asString(null));
    }

    /** Возвращает имена проверок, которые можно включить в настройки. */
    @GetMapping("/checks")
    public List<String> checks() {
        return configurations.availableChecks();
    }

    /** Возвращает имена процедур сравнения, доступных в конфигурации. */
    @GetMapping("/procedures")
    public List<String> procedures() {
        return configurations.availableProcedures();
    }

    /** Сохраняет новую конфигурацию текущей рабочей области. */
    @PostMapping
    public Configuration create(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace,
                                @RequestBody Selection selection) {
        comparisons.requireDataset(selection.datasetId());
        return configurations.save(workspace, null, selection);
    }

    /** Обновляет ранее сохранённый выбор текущей рабочей области. */
    @PostMapping("/{configurationId}")
    public Configuration update(@RequestAttribute(ComparisonWorkspaceConfig.WORKSPACE) UUID workspace,
                                @PathVariable UUID configurationId, @RequestBody Selection selection) {
        comparisons.requireDataset(selection.datasetId());
        return configurations.save(workspace, configurationId, selection);
    }
}

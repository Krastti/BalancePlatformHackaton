package com.kratti.dto.response;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Представляет версию настроек анализа, общую для обоих режимов интерфейса. */
public record AnalysisConfiguration(int schemaVersion, UUID id, String name, Source source,
                                    List<NormalizationRule> normalization, List<AnalysisField> analysisFields,
                                    List<Stage> stages, List<Check> checks, JsonNode legacyParameters) {

    /** Указывает зарегистрированный источник и формат его ответов. */
    public record Source(String kind, UUID datasetId, String format) { }

    /** Сопоставляет исходный путь с типизированным полем и ключами массивов. */
    public record NormalizationRule(List<String> sourcePath, List<String> targetPath, String type,
                                    boolean excluded, List<String> arrayKeys) { }

    /** Передаёт две таблицы черновика для предпросмотра группировки. */
    public record TablePreview(List<NormalizationRule> normalization, List<AnalysisField> analysisFields) { }

    /** Описывает поле витрины, которое доступно при настройке отчёта. */
    public record AnalysisField(List<String> path, String label, String role, List<JsonNode> buckets) { }

    /** Представляет этап в порядке исполнения и его способ запуска. */
    public record Stage(String id, String type, boolean enabled, Script script, JsonNode parameters) {
        /** Приводит явный JSON null к отсутствию параметров. */
        public Stage {
            parameters = absentIfNull(parameters);
        }
    }

    /** Представляет настроенную проверку и выбранный способ её вычисления. */
    public record Check(String id, boolean enabled, Script script, JsonNode rule, JsonNode parameters) {
        /** Приводит явный JSON null к отсутствию правила или параметров. */
        public Check {
            rule = absentIfNull(rule);
            parameters = absentIfNull(parameters);
        }
    }

    /** Описывает штатную реализацию, файл каталога или код из редактора. */
    public record Script(String kind, String file, String code) { }

    /** Определяет порядок двух независимых этапов после сравнения. */
    public boolean factsFirst() {
        return "FACTS".equals(stages.get(2).type());
    }

    /** Уравнивает отсутствующее поле и явное значение null. */
    private static JsonNode absentIfNull(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }
}

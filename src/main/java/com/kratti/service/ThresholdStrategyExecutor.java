package com.kratti.service;

import com.kratti.dto.response.ErrorDetails;
import com.kratti.dto.response.EvaluationStep;
import com.kratti.dto.response.Trace;
import com.kratti.entity.ApplicationResult;
import com.kratti.enums.Decision;
import com.kratti.entity.HistoricalApplication;
import com.kratti.enums.ResultSide;
import com.kratti.enums.ResultStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Исполняет числовые пороговые правила MVP с обязательным прохождением всех проверок. */
@Component
public class ThresholdStrategyExecutor {
    public static final String VERSION = "threshold-rules-v1";
    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "NE");

    /** Проверяет формат правил до сохранения версии стратегии. */
    public void validate(Map<String, Object> definition) {
        RequestValidation validation = new RequestValidation();
        Object value = definition.get("rules");
        if (!(value instanceof List<?> rules) || rules.isEmpty()) {
            validation.add("definition.rules", "INVALID_RULES", "Требуется непустой массив числовых правил");
            validation.rejectIfInvalid();
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < rules.size(); index++) {
            String prefix = "definition.rules[" + index + "]";
            if (!(rules.get(index) instanceof Map<?, ?> rule)) {
                validation.add(prefix, "INVALID_RULE", "Правило должно быть объектом");
                continue;
            }
            for (String field : List.of("key", "feature", "operator")) {
                if (!(rule.get(field) instanceof String text) || text.isEmpty()) {
                    validation.add(prefix + "." + field, "REQUIRED", "Требуется непустая строка");
                }
            }
            if (rule.get("key") instanceof String key && !keys.add(key)) {
                validation.add(prefix + ".key", "DUPLICATE", "Ключ правила должен быть уникальным");
            }
            if (!(rule.get("operator") instanceof String operator) || !OPERATORS.contains(operator)) {
                validation.add(prefix + ".operator", "INVALID_OPERATOR", "Допустимы GT, GTE, LT, LTE, EQ, NE");
            }
            if (!(rule.get("threshold") instanceof Number)) {
                validation.add(prefix + ".threshold", "INVALID_THRESHOLD", "Порог должен быть числом");
            }
        }
        validation.rejectIfInvalid();
    }

    /** Сохраняет фактические проверки, возвращая техническую ошибку при непригодных признаках. */
    public ApplicationResult evaluate(UUID runId, HistoricalApplication application, ResultSide side,
                                      Map<String, Object> definition) {
        validate(definition);
        List<EvaluationStep> steps = new ArrayList<>();
        boolean approved = true;
        for (Object item : (List<?>) definition.get("rules")) {
            Map<?, ?> rule = (Map<?, ?>) item;
            String key = (String) rule.get("key");
            String feature = (String) rule.get("feature");
            String operator = (String) rule.get("operator");
            Object value = application.getFeatures().get(feature);
            Map<String, Object> parameters = Map.of("feature", feature, "operator", operator,
                    "threshold", rule.get("threshold"));
            Map<String, Object> input = new LinkedHashMap<>();
            if (application.getFeatures().containsKey(feature)) {
                input.put(feature, value);
            }
            if (!(value instanceof Number number)) {
                ErrorDetails error = new ErrorDetails(value == null ? "MISSING_FEATURE" : "INVALID_FEATURE_TYPE",
                        "Для числовой проверки требуется признак " + feature);
                steps.add(new EvaluationStep(key, "RULE", "ERROR", input, parameters, Map.of(), error));
                return ApplicationResult.builder().comparisonRunId(runId).applicationId(application.getId())
                        .side(side).status(ResultStatus.ERROR).errorDetails(error).trace(new Trace(steps)).build();
            }
            boolean passed = compare(new BigDecimal(number.toString()),
                    new BigDecimal(rule.get("threshold").toString()), operator);
            approved &= passed;
            steps.add(new EvaluationStep(key, "RULE", "SUCCEEDED", input, parameters, Map.of("passed", passed), null));
        }
        return ApplicationResult.builder().comparisonRunId(runId).applicationId(application.getId()).side(side)
                .status(ResultStatus.SUCCEEDED).decision(approved ? Decision.APPROVE : Decision.DECLINE)
                .trace(new Trace(steps)).build();
    }

    /** Применяет оператор без округления десятичных порогов. */
    private boolean compare(BigDecimal value, BigDecimal threshold, String operator) {
        int comparison = value.compareTo(threshold);
        return switch (operator) {
            case "GT" -> comparison > 0;
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            case "EQ" -> comparison == 0;
            case "NE" -> comparison != 0;
            default -> throw new IllegalArgumentException("Неизвестный оператор");
        };
    }
}

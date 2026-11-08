package com.kratti.service;

import com.kratti.dto.response.EvaluationStep;
import com.kratti.entity.ApplicationResult;
import com.kratti.enums.ResultStatus;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Формирует описание только из сохранённых трасс без предположений о причинах. */
@Service
public class ExplanationService {
    /** Возвращает проверяемое описание обеих сторон либо отсутствие достаточных данных. */
    public String explain(ApplicationResult oldResult, ApplicationResult newResult) {
        if (!hasTrace(oldResult) || !hasTrace(newResult)) {
            return null;
        }
        return "Старая стратегия: " + describe(oldResult) + ". Новая стратегия: " + describe(newResult) + ".";
    }

    /** Проверяет наличие фактических входов, параметров и исходов всех проверок. */
    private boolean hasTrace(ApplicationResult result) {
        return result != null && result.getStatus() == ResultStatus.SUCCEEDED && result.getTrace() != null
                && result.getTrace().steps() != null && !result.getTrace().steps().isEmpty()
                && result.getTrace().steps().stream().allMatch(step -> "RULE".equals(step.stepType())
                && "SUCCEEDED".equals(step.status()) && step.inputValues() != null && !step.inputValues().isEmpty()
                && step.parameters() != null && step.parameters().containsKey("threshold")
                && step.parameters().containsKey("operator") && step.output() != null
                && step.output().get("passed") instanceof Boolean);
    }

    /** Перечисляет результаты правил с их фактически использованными параметрами. */
    private String describe(ApplicationResult result) {
        return result.getDecision() + "; " + result.getTrace().steps().stream().map(this::describeStep)
                .collect(Collectors.joining("; "));
    }

    /** Описывает одну проверку без назначения единственной причины при нескольких изменениях. */
    private String describeStep(EvaluationStep step) {
        return step.stepKey() + ": вход " + step.inputValues() + ", оператор " + step.parameters().get("operator")
                + ", порог " + step.parameters().get("threshold") + ", проверка "
                + (Boolean.TRUE.equals(step.output().get("passed")) ? "пройдена" : "не пройдена");
    }
}

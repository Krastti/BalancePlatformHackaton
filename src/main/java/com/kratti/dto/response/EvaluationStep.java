package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Фактически выполненная проверка с входом, параметрами и выходом. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationStep(String stepKey, String stepType, String status,
                             Map<String, Object> inputValues, Map<String, Object> parameters,
                             Map<String, Object> output, ErrorDetails errorDetails) { }

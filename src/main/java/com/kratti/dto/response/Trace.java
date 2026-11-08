package com.kratti.dto.response;

import java.util.List;

/** Упорядоченная трасса выполнения одной стратегии по заявке. */
public record Trace(List<EvaluationStep> steps) { }

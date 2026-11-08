package com.kratti.dto.response;

/** Поле и причина нарушения требований входного контракта. */
public record Violation(String field, String code, String message) { }

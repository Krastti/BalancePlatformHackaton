package com.kratti.service;

import com.kratti.dto.response.Violation;
import com.kratti.exception.ApiException;
import java.util.ArrayList;
import java.util.List;

/** Собирает нарушения обязательных полей перед атомарным сохранением данных. */
public class RequestValidation {
    private final List<Violation> violations = new ArrayList<>();

    /** Проверяет непустую строку согласно minLength контракта. */
    public void text(String value, String field) {
        if (value == null || value.isEmpty()) {
            add(field, "REQUIRED", "Требуется непустая строка");
        }
    }

    /** Проверяет наличие обязательного значения. */
    public void required(Object value, String field) {
        if (value == null) {
            add(field, "REQUIRED", "Обязательное поле отсутствует");
        }
    }

    /** Добавляет нарушение с точным путём к входному полю. */
    public void add(String field, String code, String message) {
        violations.add(new Violation(field, code, message));
    }

    /** Отклоняет весь запрос при наличии хотя бы одного нарушения. */
    public void rejectIfInvalid() {
        if (!violations.isEmpty()) {
            throw new ApiException(422, "VALIDATION_ERROR", "Данные не прошли проверку", List.copyOf(violations));
        }
    }
}

package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Структурированный ответ об ошибке HTTP-операции. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<Violation> violations) { }

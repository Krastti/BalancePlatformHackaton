package com.kratti.exception;

import com.kratti.dto.response.ApiError;
import com.kratti.dto.response.Violation;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.MismatchedInputException;

/** Возвращает ошибки API в формате контракта без раскрытия SQL и внутренних исключений. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Передаёт бизнес-ошибку с исходным HTTP-статусом и нарушениями полей. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException error) {
        return ResponseEntity.status(error.getStatus())
                .body(new ApiError(error.getCode(), error.getMessage(), error.getViolations()));
    }

    /** Отделяет синтаксические ошибки JSON и параметров от семантической валидации. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception error) {
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof MismatchedInputException mismatch && mismatch.getTargetType() != UUID.class) {
                StringBuilder field = new StringBuilder();
                for (var reference : mismatch.getPath()) {
                    if (reference.getPropertyName() != null) {
                        if (!field.isEmpty()) {
                            field.append('.');
                        }
                        field.append(reference.getPropertyName());
                    } else if (reference.getIndex() >= 0) {
                        field.append('[').append(reference.getIndex()).append(']');
                    }
                }
                Violation violation = new Violation(field.isEmpty() ? "$" : field.toString(),
                        "INVALID_TYPE", "Тип значения не соответствует контракту");
                return ResponseEntity.status(422).body(new ApiError("VALIDATION_ERROR",
                        "Данные не прошли проверку", List.of(violation)));
            }
            cause = cause.getCause();
        }
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Некорректный JSON или параметр запроса", null));
    }

    /** Преобразует гонку создания одной версии в документированный конфликт. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "uq_strategy_version".equals(violation.getConstraintName())) {
                return ResponseEntity.status(409).body(new ApiError("STRATEGY_VERSION_EXISTS",
                        "Версия с указанными strategyKey и version уже существует", null));
            }
            cause = cause.getCause();
        }
        LOG.error("Нарушение целостности данных", error);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "Не удалось сохранить данные", null));
    }
}

package com.kratti.exception;

import com.kratti.dto.response.Violation;
import java.util.List;
import lombok.Getter;

/** Ошибка бизнес-операции с HTTP-статусом и стабильным машинным кодом. */
@Getter
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<Violation> violations;

    /** Создаёт ошибку без нарушений отдельных полей. */
    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    /** Создаёт ошибку с перечнем нарушений входных данных. */
    public ApiException(int status, String code, String message, List<Violation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
    }
}

package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratti.enums.Decision;
import com.kratti.enums.ResultSide;
import com.kratti.enums.ResultStatus;
import java.time.Instant;
import java.util.UUID;

/** Сохранённый результат стороны без подмены ошибок отказами. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultView(UUID id, UUID comparisonRunId, UUID applicationId, ResultSide side,
                         ResultStatus status, Decision decision, ErrorDetails errorDetails,
                         Trace trace, Instant createdAt) { }

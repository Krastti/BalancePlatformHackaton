package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratti.enums.RunStatus;
import java.time.Instant;
import java.util.UUID;

/** Состояние запуска и каждой стороны парного расчёта. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunView(UUID id, UUID datasetId, UUID oldVersionId, UUID newVersionId,
                      String executorVersion, RunStatus status, RunStatus oldExecutionStatus,
                      RunStatus newExecutionStatus, ErrorDetails errorDetails,
                      Instant createdAt, Instant startedAt, Instant finishedAt) { }

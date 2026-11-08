package com.kratti.dto.request;

import java.util.UUID;

/** Ссылки на фиксированные входы парного сравнения. */
public record CreateComparisonRequest(UUID datasetId, UUID oldVersionId, UUID newVersionId) { }

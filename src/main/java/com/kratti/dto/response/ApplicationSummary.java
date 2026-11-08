package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratti.enums.Decision;
import com.kratti.enums.DecisionTransition;
import com.kratti.enums.PairStatus;
import java.util.UUID;

/** Строка результатов сравнения без тяжёлых признаков и трасс. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationSummary(UUID applicationId, String externalId, PairStatus processingStatus,
                                 Decision oldDecision, Decision newDecision, DecisionTransition transition) { }

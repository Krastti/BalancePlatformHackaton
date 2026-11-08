package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kratti.enums.DecisionTransition;
import com.kratti.enums.PairStatus;
import java.util.UUID;

/** Детализация заявки с подтверждённым объяснением при наличии трасс. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationDetail(UUID comparisonId, ApplicationView application, PairStatus processingStatus,
                                ResultView oldResult, ResultView newResult, DecisionTransition transition,
                                boolean explanationAvailable, String explanation) { }

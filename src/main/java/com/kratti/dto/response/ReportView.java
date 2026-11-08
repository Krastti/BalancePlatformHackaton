package com.kratti.dto.response;

import com.kratti.enums.RunStatus;
import java.math.BigDecimal;
import java.util.UUID;

/** Отчёт по одной когорте с явными null при отсутствии сравнимых данных. */
public record ReportView(UUID comparisonId, RunStatus status, long totalApplications, long comparablePairs,
                         long errorApplications, long missingApplications, BigDecimal coveragePercent,
                         long oldApprovedCount, long newApprovedCount, BigDecimal oldApprovalRatePercent,
                         BigDecimal newApprovalRatePercent, BigDecimal approvalRateChangePp,
                         long changedCount, BigDecimal changedPercent, TransitionMatrix matrix) { }

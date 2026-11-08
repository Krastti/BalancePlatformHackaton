package com.kratti.entity;

import com.kratti.dto.response.ErrorDetails;
import com.kratti.enums.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Запуск парного расчёта с зафиксированными входами и состояниями сторон. */
@Entity
@Table(name = "comparison_runs")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ComparisonRun {
    @Id
    @Builder.Default
    @Column(updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, updatable = false)
    private UUID datasetId;

    @Column(nullable = false, updatable = false)
    private UUID oldVersionId;

    @Column(nullable = false, updatable = false)
    private UUID newVersionId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String executorVersion;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private RunStatus status;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private RunStatus oldExecutionStatus;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private RunStatus newExecutionStatus;

    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ErrorDetails errorDetails;

    @Setter
    private Instant startedAt;

    @Setter
    private Instant finishedAt;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

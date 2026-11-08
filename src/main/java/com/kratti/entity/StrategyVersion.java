package com.kratti.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Неизменяемая версия стратегии с определением пороговых правил. */
@Entity
@Table(name = "strategy_versions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Immutable
public class StrategyVersion {
    @Id
    @Builder.Default
    @Column(updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String strategyKey;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String version;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> definition;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

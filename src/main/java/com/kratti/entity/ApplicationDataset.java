package com.kratti.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Неизменяемый набор исторических заявок. */
@Entity
@Table(name = "application_datasets")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Immutable
public class ApplicationDataset {
    @Id
    @Builder.Default
    @Column(updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String name;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

package com.kratti.repository;

import com.kratti.entity.HistoricalApplication;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к сохранённым данным HistoricalApplication. */
public interface HistoricalApplicationRepository extends JpaRepository<HistoricalApplication, UUID> {
    /** Возвращает заявки набора в стабильном порядке идентификаторов. */
    List<HistoricalApplication> findByDatasetIdOrderByIdAsc(UUID datasetId);

    /** Находит заявку только внутри указанного набора. */
    Optional<HistoricalApplication> findByIdAndDatasetId(UUID id, UUID datasetId);
}

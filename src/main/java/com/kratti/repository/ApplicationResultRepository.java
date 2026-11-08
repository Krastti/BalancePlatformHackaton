package com.kratti.repository;

import com.kratti.entity.ApplicationResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к сохранённым данным ApplicationResult. */
public interface ApplicationResultRepository extends JpaRepository<ApplicationResult, UUID> {
    /** Возвращает сохранённые результаты обеих сторон запуска. */
    List<ApplicationResult> findByComparisonRunId(UUID comparisonRunId);

    /** Возвращает результаты конкретной заявки внутри запуска. */
    List<ApplicationResult> findByComparisonRunIdAndApplicationId(UUID comparisonRunId, UUID applicationId);
}

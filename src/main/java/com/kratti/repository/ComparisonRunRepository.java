package com.kratti.repository;

import com.kratti.entity.ComparisonRun;
import com.kratti.enums.RunStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Доступ к сохранённым данным ComparisonRun. */
public interface ComparisonRunRepository extends JpaRepository<ComparisonRun, UUID> {
    /** Блокирует ближайший ожидающий запуск, пропуская уже захваченные строки. */
    @Query(value = "SELECT * FROM comparison_runs WHERE status = 'PENDING' ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<ComparisonRun> lockNextPending();

    /** Выбирает прерванные запуски для восстановления при старте единственного работника. */
    List<ComparisonRun> findByStatus(RunStatus status);
}

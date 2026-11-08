package com.kratti.repository;

import com.kratti.entity.StrategyVersion;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к сохранённым данным StrategyVersion. */
public interface StrategyVersionRepository extends JpaRepository<StrategyVersion, UUID> {
    /** Выбирает версии одного семейства с пагинацией. */
    Page<StrategyVersion> findByStrategyKey(String strategyKey, Pageable pageable);
}

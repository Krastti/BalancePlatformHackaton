package com.kratti.repository;

import com.kratti.entity.ApplicationDataset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к сохранённым данным ApplicationDataset. */
public interface ApplicationDatasetRepository extends JpaRepository<ApplicationDataset, UUID> {
}

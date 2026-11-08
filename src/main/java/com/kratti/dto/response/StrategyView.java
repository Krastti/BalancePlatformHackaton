package com.kratti.dto.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Версия стратегии с исходным определением. */
public record StrategyView(UUID id, String strategyKey, String version, String name,
                           Map<String, Object> definition, Instant createdAt) { }

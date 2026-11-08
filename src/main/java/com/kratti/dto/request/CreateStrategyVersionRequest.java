package com.kratti.dto.request;

import java.util.Map;

/** Определение новой неизменяемой версии стратегии. */
public record CreateStrategyVersionRequest(String strategyKey, String version, String name,
                                           Map<String, Object> definition) { }

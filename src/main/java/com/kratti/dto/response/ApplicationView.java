package com.kratti.dto.response;

import java.util.Map;
import java.util.UUID;

/** Снимок признаков заявки из конкретного набора. */
public record ApplicationView(UUID id, UUID datasetId, String externalId, Map<String, Object> features) { }

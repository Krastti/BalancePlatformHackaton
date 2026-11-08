package com.kratti.dto.request;

import java.util.Map;

/** Внешний идентификатор заявки и исходные признаки. */
public record ApplicationInput(String externalId, Map<String, Object> features) { }

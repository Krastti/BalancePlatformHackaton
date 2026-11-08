package com.kratti.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Краткие сведения о сохранённом наборе заявок. */
public record DatasetView(UUID id, String name, Instant createdAt) { }

package com.kratti.dto.request;

import java.util.List;

/** Входной набор заявок, сохраняемый атомарно. */
public record CreateDatasetRequest(String name, List<ApplicationInput> applications) { }

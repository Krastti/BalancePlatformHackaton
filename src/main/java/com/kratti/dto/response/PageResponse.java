package com.kratti.dto.response;

import java.util.List;

/** Унифицированная страница с метаданными верхнего уровня. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) { }

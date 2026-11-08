package com.kratti.service;

import com.kratti.dto.response.PageResponse;
import com.kratti.exception.ApiException;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Проверяет пагинацию и формирует страницы с согласованной метаинформацией. */
public final class Pagination {
    /** Запрещает создание служебного контейнера. */
    private Pagination() {
    }

    /** Возвращает запрос со стабильной сортировкой по дате и UUID. */
    public static PageRequest request(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(400, "BAD_REQUEST", "page должен быть неотрицательным, size — от 1 до 100");
        }
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    }

    /** Преобразует страницу хранилища в плоскую структуру OpenAPI. */
    public static <T> PageResponse<T> response(Page<T> source) {
        return new PageResponse<>(source.getContent(), source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }

    /** Выделяет страницу уже отфильтрованного списка без переполнения смещения. */
    public static <T> PageResponse<T> slice(List<T> source, int page, int size) {
        request(page, size);
        int start = (int) Math.min((long) page * size, source.size());
        int end = Math.min(start + size, source.size());
        int pages = (int) ((source.size() + (long) size - 1) / size);
        return new PageResponse<>(List.copyOf(source.subList(start, end)), page, size, source.size(), pages);
    }
}

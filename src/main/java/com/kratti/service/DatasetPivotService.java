package com.kratti.service;

import com.kratti.dto.response.DatasetPivotViews.Attribute;
import com.kratti.dto.response.DatasetPivotViews.Count;
import com.kratti.dto.response.DatasetPivotViews.Slice;
import com.kratti.dto.response.DatasetPivotViews.TreeCount;
import com.kratti.exception.ApiException;
import com.kratti.repository.DatasetPivotRepository;
import com.kratti.repository.DatasetPivotRepository.Layout;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Предоставляет сводные данные и детализацию только после публикации сравнения. */
@Service
@RequiredArgsConstructor
public class DatasetPivotService {
    private static final int PAGE_SIZE = 25;
    private final DatasetComparisonService comparisons;
    private final DatasetPivotRepository repository;

    /** Возвращает полные ячейки каждой строки страницы, сохраняя порядок измерения. */
    public Slice<Count> counts(UUID id, Layout layout, int page) {
        requirePivot(id, layout);
        List<Count> counts = repository.counts(id, layout, PAGE_SIZE, offset(page));
        int groups = 0;
        String last = null;
        for (int i = 0; i < counts.size(); i++) {
            String row = counts.get(i).row();
            if (!row.equals(last)) {
                last = row;
                if (++groups > PAGE_SIZE) {
                    return new Slice<>(counts.subList(0, i), true);
                }
            }
        }
        return new Slice<>(counts, false);
    }

    /** Возвращает уникальные счётчики всех родительских узлов дерева. */
    public List<TreeCount> treeCounts(UUID id, Layout layout) {
        requirePivot(id, layout);
        if (layout.row() != DatasetPivotRepository.RowDimension.PATH_VALUE
                && layout.row() != DatasetPivotRepository.RowDimension.FIELD_VALUES
                && layout.row() != DatasetPivotRepository.RowDimension.IMPACT) {
            throw new ApiException(400, "BAD_REQUEST", "Итоги дерева доступны только для раскладки по атрибутам");
        }
        return repository.treeCounts(id, layout);
    }

    /** Возвращает подписи полей опубликованного запуска. */
    public Map<String, String> fieldLabels(UUID id) {
        comparisons.report(id);
        return repository.fieldLabels(id);
    }

    /** Возвращает значения фильтра только для опубликованного запуска с разрезами. */
    public List<String> segmentValues(UUID id, DatasetPivotRepository.SegmentFilter segment) {
        var report = comparisons.report(id);
        if (!report.segmentsAvailable()) {
            throw new ApiException(404, "SEGMENTS_NOT_FOUND", "У запуска нет сохранённых разрезов заявок");
        }
        return repository.segmentValues(id, segment);
    }

    /** Возвращает значения строк или столбцов для фильтра опубликованной сводной. */
    public List<String> dimensionValues(UUID id, Layout layout, DatasetPivotRepository.FilterAxis axis) {
        requirePivot(id, layout);
        return repository.dimensionValues(id, layout, axis);
    }

    /** Возвращает страницу списка заявок внутри выбранной ячейки. */
    public Slice<String> applications(UUID id, Layout layout, String row, String column,
                                      DatasetPivotRepository.GroupSelection group, int page) {
        requirePivot(id, layout);
        validateSelection(layout, row, group);
        return slice(repository.applications(id, layout, row, column, group, PAGE_SIZE, offset(page)));
    }

    /** Возвращает страницу атрибутов выбранной заявки, попавших в ячейку. */
    public Slice<Attribute> attributes(UUID id, Layout layout, String row, String column,
                                       DatasetPivotRepository.GroupSelection group, String app, int page) {
        requirePivot(id, layout);
        validateSelection(layout, row, group);
        return slice(repository.attributes(id, layout, row, column, group, app, PAGE_SIZE, offset(page)));
    }

    /** Требует ровно одну координату для детализации листа или группы. */
    private void validateSelection(Layout layout, String row, DatasetPivotRepository.GroupSelection group) {
        if ((group == null) == (row == null || row.isBlank())) {
            throw new ApiException(400, "BAD_REQUEST", "Нужно выбрать ячейку или группу сводной таблицы");
        }
        if (group != null && layout.row() != DatasetPivotRepository.RowDimension.PATH_VALUE
                && layout.row() != DatasetPivotRepository.RowDimension.FIELD_VALUES
                && layout.row() != DatasetPivotRepository.RowDimension.IMPACT) {
            throw new ApiException(400, "BAD_REQUEST", "Группы доступны только в раскладке по атрибутам");
        }
    }

    /** Отклоняет запросы по старым запускам без сохранённых фактов. */
    private void requirePivot(UUID id, Layout layout) {
        var report = comparisons.report(id);
        if (!report.pivotAvailable()) {
            throw new ApiException(404, "PIVOT_NOT_FOUND", "У запуска нет фактов для сводной таблицы");
        }
        if (layout.segment() != null && !report.segmentsAvailable()) {
            throw new ApiException(404, "SEGMENTS_NOT_FOUND", "У запуска нет сохранённых разрезов заявок");
        }
    }

    /** Проверяет номер страницы перед расчётом смещения. */
    private long offset(int page) {
        if (page < 0) {
            throw new ApiException(400, "BAD_REQUEST", "Номер страницы должен быть неотрицательным");
        }
        return (long) page * PAGE_SIZE;
    }

    /** Убирает контрольную строку, если за страницей есть продолжение. */
    private <T> Slice<T> slice(List<T> items) {
        boolean hasNext = items.size() > PAGE_SIZE;
        return new Slice<>(hasNext ? items.subList(0, PAGE_SIZE) : items, hasNext);
    }
}

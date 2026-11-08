package com.kratti.controller;

import com.kratti.dto.response.DatasetPivotViews.Attribute;
import com.kratti.dto.response.DatasetPivotViews.Count;
import com.kratti.dto.response.DatasetPivotViews.Slice;
import com.kratti.dto.response.DatasetPivotViews.TreeCount;
import com.kratti.exception.ApiException;
import com.kratti.repository.DatasetPivotRepository.ColumnDimension;
import com.kratti.repository.DatasetPivotRepository.FilterAxis;
import com.kratti.repository.DatasetPivotRepository.GroupLevel;
import com.kratti.repository.DatasetPivotRepository.GroupSelection;
import com.kratti.repository.DatasetPivotRepository.Layout;
import com.kratti.repository.DatasetPivotRepository.RowDimension;
import com.kratti.repository.DatasetPivotRepository.SegmentFilter;
import com.kratti.service.DatasetPivotService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Отдаёт ячейки сводной и данные для перехода от них к заявкам и атрибутам. */
@RestController
@RequestMapping("/api/comparisons/{id}/pivot")
@RequiredArgsConstructor
public class DatasetPivotController {
    private final DatasetPivotService pivot;

    /** Возвращает страницу фактов для выбранной раскладки сводной таблицы. */
    @GetMapping
    public Slice<Count> counts(@PathVariable UUID id, @RequestParam(defaultValue = "PATH") RowDimension rows,
            @RequestParam(defaultValue = "SOURCE_DIFF") ColumnDimension columns,
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(required = false) SegmentFilter segment,
            @RequestParam(required = false) List<String> segmentValue,
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(defaultValue = "false") boolean filterRows,
            @RequestParam(defaultValue = "false") boolean filterColumns,
            @RequestParam(defaultValue = "0") int page) {
        return pivot.counts(id, layout(rows, columns, prefix, segment, segmentValue, query.get("rowValue"), query.get("columnValue"),
                filterRows, filterColumns), page);
    }

    /** Возвращает пользовательские подписи полей, сохранённые в снимке запуска. */
    @GetMapping("/field-labels")
    public Map<String, String> fieldLabels(@PathVariable UUID id) {
        return pivot.fieldLabels(id);
    }

    /** Возвращает уникальные итоги для родительских узлов дерева сводной таблицы. */
    @GetMapping("/tree-counts")
    public List<TreeCount> treeCounts(@PathVariable UUID id, @RequestParam RowDimension rows,
            @RequestParam(defaultValue = "SOURCE_DIFF") ColumnDimension columns,
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(required = false) SegmentFilter segment,
            @RequestParam(required = false) List<String> segmentValue,
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(defaultValue = "false") boolean filterRows,
            @RequestParam(defaultValue = "false") boolean filterColumns) {
        return pivot.treeCounts(id, layout(rows, columns, prefix, segment, segmentValue, query.get("rowValue"), query.get("columnValue"),
                filterRows, filterColumns));
    }

    /** Возвращает реальные значения выбранного фильтра по разрезу заявок. */
    @GetMapping("/segment-values")
    public List<String> segmentValues(@PathVariable UUID id, @RequestParam SegmentFilter segment) {
        return pivot.segmentValues(id, segment);
    }

    /** Возвращает значения выбранной оси для построения списка Excel-подобного фильтра. */
    @GetMapping("/dimension-values")
    public List<String> dimensionValues(@PathVariable UUID id, @RequestParam RowDimension rows,
            @RequestParam(defaultValue = "SOURCE_DIFF") ColumnDimension columns,
            @RequestParam(defaultValue = "") String prefix, @RequestParam FilterAxis axis,
            @RequestParam(required = false) SegmentFilter segment,
            @RequestParam(required = false) List<String> segmentValue) {
        return pivot.dimensionValues(id, layout(rows, columns, prefix, segment, segmentValue, null, null,
                false, false), axis);
    }

    /** Возвращает заявки из выбранной ячейки или родительской группы дерева. */
    @GetMapping("/applications")
    public Slice<String> applications(@PathVariable UUID id, @RequestParam RowDimension rows,
            @RequestParam ColumnDimension columns, @RequestParam(defaultValue = "") String prefix,
            @RequestParam(required = false) SegmentFilter segment,
            @RequestParam(required = false) List<String> segmentValue,
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(defaultValue = "false") boolean filterRows,
            @RequestParam(defaultValue = "false") boolean filterColumns,
            @RequestParam(required = false) GroupLevel groupLevel,
            @RequestParam(required = false) List<String> groupPath,
            @RequestParam(required = false) String row, @RequestParam String column,
            @RequestParam(defaultValue = "0") int page) {
        return pivot.applications(id, layout(rows, columns, prefix, segment, segmentValue, query.get("rowValue"), query.get("columnValue"),
                filterRows, filterColumns),
                row, column, group(groupLevel, groupPath), page);
    }

    /** Возвращает атрибуты заявки, входящие в ту же ячейку или группу сводной. */
    @GetMapping("/applications/{applicationId}/attributes")
    public Slice<Attribute> attributes(@PathVariable UUID id, @PathVariable String applicationId,
            @RequestParam RowDimension rows, @RequestParam ColumnDimension columns,
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(required = false) SegmentFilter segment,
            @RequestParam(required = false) List<String> segmentValue,
            @RequestParam MultiValueMap<String, String> query,
            @RequestParam(defaultValue = "false") boolean filterRows,
            @RequestParam(defaultValue = "false") boolean filterColumns,
            @RequestParam(required = false) GroupLevel groupLevel,
            @RequestParam(required = false) List<String> groupPath,
            @RequestParam(required = false) String row, @RequestParam String column,
            @RequestParam(defaultValue = "0") int page) {
        return pivot.attributes(id, layout(rows, columns, prefix, segment, segmentValue, query.get("rowValue"), query.get("columnValue"),
                filterRows, filterColumns),
                row, column, group(groupLevel, groupPath), applicationId, page);
    }

    /** Проверяет полноту пути группы перед передачей его в слой данных. */
    private GroupSelection group(GroupLevel level, List<String> path) {
        if (level == null && (path == null || path.isEmpty())) return null;
        if (level == null || path == null || path.size() != level.ordinal() + 1) {
            throw new ApiException(400, "BAD_REQUEST", "Неверный путь группы сводной таблицы");
        }
        return new GroupSelection(level, List.copyOf(path));
    }

    /** Проверяет фильтры раскладки и создаёт их неизменяемое представление. */
    private Layout layout(RowDimension rows, ColumnDimension columns, String prefix, SegmentFilter segment,
            List<String> segmentValue, List<String> rowValue, List<String> columnValue,
            boolean filterRows, boolean filterColumns) {
        List<String> segments = segmentValue == null ? List.of() : List.copyOf(segmentValue);
        List<String> rowValues = rowValue == null ? List.of() : List.copyOf(rowValue);
        List<String> columnValues = columnValue == null ? List.of() : List.copyOf(columnValue);
        if (segment == null && !segments.isEmpty()) {
            throw new ApiException(400, "BAD_REQUEST", "Для отбора значений нужно поле разреза");
        }
        if (segments.stream().anyMatch(String::isBlank)) {
            throw new ApiException(400, "BAD_REQUEST", "Пустое значение нельзя использовать как фильтр разреза");
        }
        return new Layout(rows, columns, prefix, segment, segments, rowValues, columnValues, filterRows, filterColumns);
    }
}

package com.kratti.repository;

import com.kratti.dto.response.DatasetPivotViews.Attribute;
import com.kratti.dto.response.DatasetPivotViews.Count;
import com.kratti.dto.response.DatasetPivotViews.TreeCount;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Агрегирует число уникальных заявок и использует те же факты для детализации. */
@Repository
@RequiredArgsConstructor
public class DatasetPivotRepository {
    /** Выбирает выражение для строки из фиксированного набора измерений витрины. */
    public enum RowDimension {
        PATH("path"), PATH_VALUE("jsonb_build_array(a.prefix, a.attribute_class, a.suffix, a.array_key, a.field_key, a.adjusted_value)::text"),
        FIELD_VALUES(PATH_VALUE.expression), IMPACT(PATH_VALUE.expression), PREFIX("prefix"), CLASS("attribute_class"), SUFFIX("suffix"),
        KEY("field_key"), ARRAY_KEY("array_key"), VALUE("a.adjusted_value");

        private final String expression;

        /** Связывает разрешённое измерение с выражением в запросе. */
        RowDimension(String expression) {
            this.expression = expression;
        }
    }

    /** Выбирает разбиение столбцов по срезу, признаку изменения или типу изменения. */
    public enum ColumnDimension {
        SOURCE("a.source"), CHANGE_TYPE("change_type"),
        DIFF_FLAG("CASE WHEN change_type = 'SAME' THEN '0' ELSE '1' END"),
        SOURCE_DIFF("a.source || ' / ' || CASE WHEN change_type = 'SAME' THEN '0' ELSE '1' END");

        private final String expression;

        /** Связывает разрешённое измерение с выражением в запросе. */
        ColumnDimension(String expression) {
            this.expression = expression;
        }
    }

    /** Выбирает поле разреза для буквального отбора заявок. */
    public enum SegmentFilter {
        PRODUCT("EXISTS (SELECT 1 FROM unnest(string_to_array(s.products, ';')) AS selected_segment(segment_value) "
                + "WHERE segment_value IN (:segmentValues))"),
        PARTNER_TYPE("EXISTS (SELECT 1 FROM unnest(string_to_array(s.partner_types, ';')) AS selected_segment(segment_value) "
                + "WHERE segment_value IN (:segmentValues))"),
        ROUTING_METHOD("EXISTS (SELECT 1 FROM unnest(string_to_array(s.routing_methods, ';')) AS selected_segment(segment_value) "
                + "WHERE segment_value IN (:segmentValues))"),
        PD_MODEL_AFTER("s.pd_model_after IN (:segmentValues)");

        private final String condition;

        /** Связывает разрешённый фильтр с условием над слоем разрезов. */
        SegmentFilter(String condition) {
            this.condition = condition;
        }
    }

    /** Уровень пути, который пользователь раскрыл из сводной таблицы. */
    public enum GroupLevel {
        PREFIX, CLASS, SUFFIX, ARRAY_KEY, KEY
    }

    /** Ось сводной, значения которой можно отбирать как в табличном редакторе. */
    public enum FilterAxis {
        ROW, COLUMN
    }

    /** Ключ узла дерева, используемый для подсчёта и просмотра заявок. */
    public record GroupSelection(GroupLevel level, List<String> path) { }

    /** Описывает измерения и фильтры, которые одинаково применяются к ячейке и её деталям. */
    public record Layout(RowDimension row, ColumnDimension column, String pathPrefix,
                         SegmentFilter segment, List<String> segmentValues,
                         List<String> rowValues, List<String> columnValues,
                         boolean filterRows, boolean filterColumns) { }

    private static final String FACTS = """
            FROM dataset_pivot_slices a
            WHERE a.comparison_id = :id AND starts_with(a.path, :prefix)
                AND (NOT EXISTS (
                    SELECT 1 FROM dataset_comparisons c
                    WHERE c.id = :id AND jsonb_array_length(
                        COALESCE(c.configuration_snapshot #> '{analysis,analysisFields}', '[]'::jsonb)) > 0
                ) OR a.path IN (
                    SELECT (SELECT string_agg(part, '.' ORDER BY position)
                            FROM jsonb_array_elements_text(field -> 'path') WITH ORDINALITY AS p(part, position))
                    FROM dataset_comparisons c
                    CROSS JOIN LATERAL jsonb_array_elements(
                        COALESCE(c.configuration_snapshot #> '{analysis,analysisFields}', '[]'::jsonb)) field
                    WHERE c.id = :id
                ))
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    /** Читает подписи выбранных полей из снимка запуска. */
    public Map<String, String> fieldLabels(UUID id) {
        return jdbc.sql("""
                SELECT (SELECT string_agg(part, '.' ORDER BY position)
                        FROM jsonb_array_elements_text(field -> 'path') WITH ORDINALITY AS p(part, position)),
                        field ->> 'label'
                FROM dataset_comparisons c
                CROSS JOIN LATERAL jsonb_array_elements(
                    COALESCE(c.configuration_snapshot #> '{analysis,analysisFields}', '[]'::jsonb)) field
                WHERE c.id = ?
                """).param(id).query((row, index) -> Map.entry(row.getString(1), row.getString(2)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Возвращает доступные значения выбранного измерения слоя разрезов. */
    public List<String> segmentValues(UUID id, SegmentFilter segment) {
        String sql = switch (segment) {
            case PRODUCT -> "SELECT DISTINCT value FROM dataset_comparison_segments s "
                    + "CROSS JOIN LATERAL unnest(string_to_array(s.products, ';')) value "
                    + "WHERE s.comparison_id = :id AND value <> '' ORDER BY value";
            case PARTNER_TYPE -> "SELECT DISTINCT value FROM dataset_comparison_segments s "
                    + "CROSS JOIN LATERAL unnest(string_to_array(s.partner_types, ';')) value "
                    + "WHERE s.comparison_id = :id AND value <> '' ORDER BY value";
            case ROUTING_METHOD -> "SELECT DISTINCT value FROM dataset_comparison_segments s "
                    + "CROSS JOIN LATERAL unnest(string_to_array(s.routing_methods, ';')) value "
                    + "WHERE s.comparison_id = :id AND value <> '' ORDER BY value";
            case PD_MODEL_AFTER -> "SELECT DISTINCT pd_model_after FROM dataset_comparison_segments "
                    + "WHERE comparison_id = :id AND pd_model_after IS NOT NULL ORDER BY pd_model_after";
        };
        return jdbc.sql(sql).param("id", id).query(String.class).list();
    }

    /** Возвращает полный список значений одной оси для Excel-подобного фильтра. */
    public List<String> dimensionValues(UUID id, Layout layout, FilterAxis axis) {
        String expression = axis == FilterAxis.ROW ? layout.row().expression : layout.column().expression;
        String sql = "SELECT value FROM (SELECT DISTINCT " + expression + " AS value "
                + factsSource(layout) + ") options ORDER BY value COLLATE \"C\"";
        return facts(sql, id, layout).query(String.class).list();
    }

    /** Читает страницу групп и одну дополнительную группу для навигации. */
    public List<Count> counts(UUID id, Layout layout, int size, long offset) {
        if (layout.row() == RowDimension.IMPACT) {
            return impactCounts(id, layout, size, offset);
        }
        String sql = """
                WITH counts AS (
                    SELECT %s AS row_value, %s AS column_value, count(DISTINCT application_id) AS cnt
                    %s GROUP BY 1, 2
                ), ranked AS (
                    SELECT *, dense_rank() OVER (ORDER BY row_value COLLATE "C") AS row_number FROM counts
                )
                SELECT row_value, column_value, cnt FROM ranked
                WHERE row_number > :offset AND row_number <= :end
                ORDER BY row_number, column_value COLLATE "C"
                """.formatted(layout.row().expression, layout.column().expression, factsSource(layout));
        return facts(sql, id, layout).param("offset", offset).param("end", offset + size + 1)
                .query((row, index) -> new Count(row.getString(1), row.getString(2), row.getLong(3), null)).list();
    }

    /** Считает уникальные заявки для каждого родительского узла и столбца. */
    public List<TreeCount> treeCounts(UUID id, Layout layout) {
        String source = factsSource(layout);
        String column = layout.column().expression;
        String sql = """
                WITH base AS (
                    SELECT DISTINCT application_id, %s AS column_value,
                        COALESCE(a.prefix, '') AS prefix, COALESCE(a.attribute_class, '') AS attribute_class,
                        COALESCE(a.suffix, '') AS suffix, COALESCE(a.array_key, '') AS array_key,
                        COALESCE(a.field_key, '') AS field_key
                    %s
                ), groups AS (
                    SELECT 'PREFIX' AS level, jsonb_build_array(prefix)::text AS path, column_value, application_id FROM base
                    UNION ALL SELECT 'CLASS', jsonb_build_array(prefix, attribute_class)::text, column_value, application_id FROM base
                    UNION ALL SELECT 'SUFFIX', jsonb_build_array(prefix, attribute_class, suffix)::text, column_value, application_id
                        FROM base WHERE suffix <> ''
                    UNION ALL SELECT 'ARRAY_KEY', jsonb_build_array(prefix, attribute_class, suffix, array_key)::text,
                        column_value, application_id FROM base WHERE array_key <> ''
                    UNION ALL SELECT 'KEY', jsonb_build_array(prefix, attribute_class, suffix, array_key, field_key)::text,
                        column_value, application_id FROM base
                )
                SELECT level, path, column_value, count(DISTINCT application_id) AS cnt
                FROM groups GROUP BY level, path, column_value
                ORDER BY level, path COLLATE "C", column_value COLLATE "C"
                """.formatted(column, source);
        return facts(sql, id, layout).query((row, index) ->
                new TreeCount(row.getString(1), path(row.getString(2)), row.getString(3), row.getLong(4))).list();
    }

    /** Преобразует путь, собранный SQL как JSON-массив, в типизированный список. */
    private List<String> path(String value) {
        return json.readValue(value, new TypeReference<>() { });
    }

    /** Сортирует изменившиеся поля по уникальным заявкам во всём выбранном разрезе. */
    private List<Count> impactCounts(UUID id, Layout layout, int size, long offset) {
        String sql = """
                WITH changed AS MATERIALIZED (
                    SELECT a.path, a.prefix, a.attribute_class, a.suffix, a.array_key, a.field_key,
                        a.adjusted_value, a.source, a.change_type, a.application_id
                    %s
                ), impact AS (
                    SELECT path, count(DISTINCT application_id) AS affected FROM changed GROUP BY path
                ), counts AS (
                    SELECT a.path, %s AS row_value, %s AS column_value, count(DISTINCT application_id) AS cnt
                    FROM changed a GROUP BY 1, 2, 3
                ), ranked AS (
                    SELECT row_value, column_value, cnt, affected,
                        dense_rank() OVER (ORDER BY affected DESC, path COLLATE "C", row_value COLLATE "C") AS row_number
                    FROM counts JOIN impact USING (path)
                )
                SELECT row_value, column_value, cnt, affected FROM ranked
                WHERE row_number > :offset AND row_number <= :end
                ORDER BY row_number, column_value COLLATE "C"
                """.formatted(factsSource(layout), layout.row().expression, layout.column().expression);
        return facts(sql, id, layout).param("offset", offset).param("end", offset + size + 1)
                .query((row, index) -> new Count(row.getString(1), row.getString(2), row.getLong(3), row.getLong(4)))
                .list();
    }

    /** Читает идентификаторы заявок, посчитанных в выбранной ячейке. */
    public List<String> applications(UUID id, Layout layout, String row, String column, GroupSelection group,
                                     int size, long offset) {
        String sql = "SELECT DISTINCT application_id " + cell(layout, group)
                + " ORDER BY application_id LIMIT :limit OFFSET :offset";
        var query = groupFacts(sql, id, layout, group, row, column);
        return query
                .param("limit", size + 1).param("offset", offset).query(String.class).list();
    }

    /** Читает конкретные атрибуты заявки, повлиявшие на выбранную ячейку. */
    public List<Attribute> attributes(UUID id, Layout layout, String row, String column, GroupSelection group,
                                      String applicationId,
                                      int size, long offset) {
        String sql = "SELECT DISTINCT path, array_key, value_before, value_after, change_type " + cell(layout, group)
                + " AND application_id = :app ORDER BY path, array_key LIMIT :limit OFFSET :offset";
        return groupFacts(sql, id, layout, group, row, column).param("app", applicationId)
                .param("limit", size + 1).param("offset", offset)
                .query((value, index) -> new Attribute(value.getString(1), value.getString(2),
                        value.getString(3), value.getString(4), value.getString(5))).list();
    }

    /** Применяет обе координаты ячейки к общему набору фактов. */
    private String cell(Layout layout, GroupSelection group) {
        String selected = group == null ? "(" + layout.row().expression + ") = :row" : groupCondition(group.level());
        return factsSource(layout) + " AND " + selected + " AND (" + layout.column().expression + ") = :column";
    }

    /** Добавляет параметры обычной ячейки либо узла дерева. */
    private JdbcClient.StatementSpec groupFacts(String sql, UUID id, Layout layout, GroupSelection group,
                                               String row, String column) {
        var query = facts(sql, id, layout).param("column", column);
        if (group == null) {
            return query.param("row", row);
        }
        for (int index = 0; index < group.path().size(); index++) {
            query = query.param("group" + index, group.path().get(index));
        }
        return query;
    }

    /** Формирует безопасное сравнение по фиксированному уровню иерархии. */
    private String groupCondition(GroupLevel level) {
        return switch (level) {
            case PREFIX -> "COALESCE(a.prefix, '') = :group0";
            case CLASS -> "COALESCE(a.prefix, '') = :group0 AND COALESCE(a.attribute_class, '') = :group1";
            case SUFFIX -> "COALESCE(a.prefix, '') = :group0 AND COALESCE(a.attribute_class, '') = :group1 "
                    + "AND COALESCE(a.suffix, '') = :group2";
            case ARRAY_KEY -> "COALESCE(a.prefix, '') = :group0 AND COALESCE(a.attribute_class, '') = :group1 "
                    + "AND COALESCE(a.suffix, '') = :group2 AND COALESCE(a.array_key, '') = :group3";
            case KEY -> "COALESCE(a.prefix, '') = :group0 AND COALESCE(a.attribute_class, '') = :group1 "
                    + "AND COALESCE(a.suffix, '') = :group2 AND COALESCE(a.array_key, '') = :group3 "
                    + "AND COALESCE(a.field_key, '') = :group4";
        };
    }

    /** Добавляет отбор по разрезам только при выборе соответствующего поля. */
    private String factsSource(Layout layout) {
        String source = switch (layout.row()) {
            case IMPACT -> FACTS + " AND a.change_type <> 'SAME' ";
            case FIELD_VALUES -> FACTS.replace("starts_with(a.path, :prefix)", "a.path = :prefix");
            default -> FACTS;
        };
        if (layout.segment() == null) {
            return dimensionFilters(source, layout);
        }
        if (layout.segmentValues().isEmpty()) {
            return dimensionFilters(source + " AND FALSE ", layout);
        }
        return dimensionFilters(source + """
                 AND a.application_id = ANY (ARRAY(SELECT s.application_id FROM dataset_comparison_segments s
                    WHERE s.comparison_id = :id AND %s))
                """.formatted(layout.segment().condition), layout);
    }

    /** Добавляет точные отборы значений строк и столбцов к общему набору фактов. */
    private String dimensionFilters(String source, Layout layout) {
        if (layout.filterRows()) {
            source += layout.rowValues().isEmpty()
                    ? " AND FALSE " : " AND (" + layout.row().expression + ") IN (:rowValues) ";
        }
        if (layout.filterColumns()) {
            source += layout.columnValues().isEmpty()
                    ? " AND FALSE " : " AND (" + layout.column().expression + ") IN (:columnValues) ";
        }
        return source;
    }

    /** Привязывает идентификатор и префикс к запросам по одной схеме. */
    private JdbcClient.StatementSpec facts(String sql, UUID id, Layout layout) {
        var query = jdbc.sql(sql).param("id", id).param("prefix", layout.pathPrefix());
        if (layout.segment() != null && !layout.segmentValues().isEmpty()) {
            query = query.param("segmentValues", layout.segmentValues());
        }
        if (layout.filterRows() && !layout.rowValues().isEmpty()) {
            query = query.param("rowValues", layout.rowValues());
        }
        if (layout.filterColumns() && !layout.columnValues().isEmpty()) {
            query = query.param("columnValues", layout.columnValues());
        }
        return query;
    }
}

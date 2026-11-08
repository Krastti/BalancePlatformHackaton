package com.kratti.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Описывает группы сводной таблицы и переходы от них к заявкам и полям. */
public final class DatasetPivotViews {
    /** Исключает создание экземпляра контейнера ответов. */
    private DatasetPivotViews() { }

    /** Строка фактов counts с количеством уникальных заявок в ячейке. */
    public record Count(String row, String column, long count,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Long affectedApplications) { }

    /** Уникальное число заявок в одном узле иерархии сводной таблицы. */
    public record TreeCount(String level, List<String> path, String column, long count) { }

    /** Страница с признаком наличия следующих данных. */
    public record Slice<T>(List<T> items, boolean hasNext) { }

    /** Атрибут, попавший в выбранную ячейку, со значениями обоих срезов. */
    public record Attribute(String path, String arrayKey, String valueBefore, String valueAfter, String changeType) { }
}

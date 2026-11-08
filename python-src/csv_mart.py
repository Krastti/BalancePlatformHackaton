"""Индексированный доступ к витрине сравнения и разрезам заявок."""

import csv
from bisect import bisect_left
from collections import defaultdict
from sys import intern

from compare_responses import COLUMNS


FILTER_COLUMNS = {
    "path": "PATH",
    "key": "KEY",
    "array_key": "ARRAY_KEY",
    "diff_flag": "DIFF_FLAG",
    "change_type": "CHANGE_TYPE",
    "value_before": "VALUE_BEFORE",
    "value_after": "VALUE_AFTER",
    "app_ids": "APP_ID",
}
TRIAL = "decisionResult.product_trials."
OFFER = "decisionResult.available_offers."
SEGMENT_PATHS = {
    TRIAL + "product_id",
    TRIAL + "partner_type",
    TRIAL + "routing_method",
    TRIAL + "scoring.pd.modelName",
    TRIAL + "cutOff.name",
    TRIAL + "rejectReason",
    OFFER + "product_id",
    OFFER + "rank",
    "characteristics.score_okb",
}

SEGMENT_COLUMNS = (
    "APP_ID", "DECISION_BEFORE", "DECISION_AFTER", "HAS_DIFF", "DECISION_CHANGED",
    "PRODUCTS", "PARTNER_TYPE", "ROUTING_METHOD",
    *(f"{field}_{source}" for field in ("TOP_OFFER", "PD_MODEL", "CUTOFF", "REJECT_REASON", "SCORE_OKB")
      for source in ("BEFORE", "AFTER")),
)


def read_csv(path, required):
    """Читает строки CSV, отклоняя несовместимую схему и повреждённые строки."""
    with path.open(encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, delimiter=";", strict=True)
        if not required.issubset(reader.fieldnames or ()):
            raise ValueError(f"{path}: отсутствуют колонки {sorted(required - set(reader.fieldnames or ()))}")
        for row in reader:
            if None in row or None in row.values():
                raise ValueError(f"{path}:{reader.line_num}: неверное число колонок")
            yield row


def segment(application, slices):
    """Сводит поля обоих срезов к одному разрезу заявки."""
    result = dict(application)
    products = set()
    partner_types = set()
    routing_methods = set()
    for source, fields in slices.items():
        trials = fields.get(TRIAL + "product_id", {})
        products.update(trials.values())
        partner_types.update(fields.get(TRIAL + "partner_type", {}).values())
        routing_methods.update(fields.get(TRIAL + "routing_method", {}).values())
        offers = fields.get(OFFER + "product_id", {})
        ranks = fields.get(OFFER + "rank", {})
        ranked = [(int(rank), key) for key, rank in ranks.items() if key in offers and rank.isdecimal()]
        top = min(ranked)[1] if ranked else None
        top_product = offers.get(top)
        result[f"TOP_OFFER_{source}"] = top_product
        trial_key = next((key for key in sorted(trials) if trials[key] == top_product), None)
        if trial_key is None:
            trial_key = min(trials, default=None)

        for name, path in (
            ("PD_MODEL", TRIAL + "scoring.pd.modelName"),
            ("CUTOFF", TRIAL + "cutOff.name"),
            ("REJECT_REASON", TRIAL + "rejectReason"),
        ):
            result[f"{name}_{source}"] = fields.get(path, {}).get(trial_key)
        result[f"SCORE_OKB_{source}"] = fields.get("characteristics.score_okb", {}).get("")
    result["PRODUCTS"] = ";".join(sorted(products))
    result["PARTNER_TYPE"] = ";".join(sorted(partner_types))
    result["ROUTING_METHOD"] = ";".join(sorted(routing_methods))
    return result


def load_segments(path, applications):
    """Читает стандартные разрезы и сверяет их ключи и решения с заявками."""
    segments = {}
    for row in read_csv(path, set(SEGMENT_COLUMNS)):
        app_id = row["APP_ID"]
        application = applications.get(app_id)
        if application is None or app_id in segments:
            raise ValueError(f"{path}: неизвестный или повторяющийся APP_ID {app_id!r}")
        row["DECISION_CHANGED"] = int(row["DECISION_CHANGED"])
        row["HAS_DIFF"] = int(row["HAS_DIFF"])
        if any(row.get(key) != value for key, value in application.items()):
            raise ValueError(f"{path}: разрез расходится с данными заявки {app_id!r}")
        for key in SEGMENT_COLUMNS[8:]:
            row[key] = row[key] or None
        segments[app_id] = row
    if segments.keys() != applications.keys():
        raise ValueError(f"{path}: часть заявок осталась без разрезов")
    return list(segments.values())


class CsvMart:
    """Реализует MartAPI поверх файлов стандартного прогона."""

    def __init__(self, results):
        self._rows = []
        self._indexes = {name: defaultdict(list) for name in FILTER_COLUMNS}
        applications = {}
        required = {"APP_ID", "DECISION_BEFORE", "DECISION_AFTER", "DECISION_CHANGED"}
        for row in read_csv(results / "applications.csv", required):
            app_id = row["APP_ID"]
            if not app_id or app_id in applications:
                raise ValueError(f"Некорректный или повторяющийся APP_ID: {app_id!r}")
            row["DECISION_CHANGED"] = int(row["DECISION_CHANGED"])
            if "HAS_DIFF" in row:
                row["HAS_DIFF"] = int(row["HAS_DIFF"])
            applications[app_id] = row
        segment_file = results / "segments.csv"
        has_segments = segment_file.is_file()
        slices = {} if has_segments else {
            app_id: {source: defaultdict(dict) for source in ("BEFORE", "AFTER")}
            for app_id in applications
        }

        for row in read_csv(results / "comparison.csv", set(COLUMNS)):
            row = {key: intern(value) for key, value in row.items()}
            row["DIFF_FLAG"] = int(row["DIFF_FLAG"])
            app_id, path = row["APP_ID"], row["PATH"]
            if app_id not in applications:
                raise ValueError(f"Витрина содержит неизвестный APP_ID: {app_id}")
            position = len(self._rows)
            self._rows.append(row)
            for name, column in FILTER_COLUMNS.items():
                self._indexes[name][row[column]].append(position)

            if not has_segments and path in SEGMENT_PATHS:
                for source, absent in (("BEFORE", "ATTRIBUTE_NEW"), ("AFTER", "ATTRIBUTE_LOST")):
                    value = row[f"VALUE_{source}"]
                    if row["CHANGE_TYPE"] != absent and value not in ("", "NULL"):
                        slices[app_id][source][path][row["ARRAY_KEY"]] = value

        if has_segments:
            self._segments = load_segments(segment_file, applications)
        else:
            self._segments = [segment(row, slices[app_id]) for app_id, row in applications.items()]
        self._paths = sorted(self._indexes["path"])

    def total_apps(self):
        """Возвращает число заявок независимо от количества их атрибутов."""
        return len(self._segments)

    def segments(self):
        """Возвращает по одному разрезу на заявку."""
        return (row.copy() for row in self._segments)

    def _positions(self, filters):
        """Пересекает результаты фильтров и сохраняет порядок строк витрины."""
        selections = []
        for name, value in filters.items():
            if name == "path_prefix":
                index = self._indexes["path"]
                start = bisect_left(self._paths, value)
                paths = []
                for position in range(start, len(self._paths)):
                    path = self._paths[position]
                    if not path.startswith(value):
                        break
                    paths.append(path)
                values = paths
            else:
                if name not in FILTER_COLUMNS:
                    raise TypeError(f"Неизвестный фильтр MartAPI: {name}")
                index = self._indexes[name]
                values = [value] if isinstance(value, (str, int)) else value

            selections.append([index.get(item, ()) for item in dict.fromkeys(values)])

        if not selections:
            return range(len(self._rows))
        selections.sort(key=lambda groups: sum(map(len, groups)))
        if len(selections) == 1 and len(selections[0]) == 1:
            return selections[0][0]
        positions = {position for group in selections[0] for position in group}
        for groups in selections[1:]:
            if not positions:
                break
            positions.intersection_update(position for group in groups for position in group)
        return sorted(positions)

    def rows(self, **filters):
        """Возвращает независимые копии строк, удовлетворяющих всем фильтрам."""
        return (self._rows[position].copy() for position in self._positions(filters))

    def count(self, **filters):
        """Считает строки витрины по тем же фильтрам."""
        return len(self._positions(filters))

    def app_ids(self, **filters):
        """Возвращает отсортированные уникальные идентификаторы заявок."""
        return sorted({self._rows[position]["APP_ID"] for position in self._positions(filters)})

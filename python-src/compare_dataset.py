"""Сравнивает все пары ответов СПР по манифесту датасета."""

import argparse
import csv
import json
import os
from decimal import Decimal
from itertools import groupby
from pathlib import Path
from tempfile import TemporaryDirectory

from compare_responses import COLUMNS, compare_responses, compile_rules, read_response, scalar
from csv_mart import SEGMENT_COLUMNS, SEGMENT_PATHS, segment


DECISION = (("decisionResult", "decision"), "")
SEGMENT_FIELDS = {tuple(path.split(".")): path for path in SEGMENT_PATHS}
MAX_NORMALIZED_LINE = 256 * 1024
MAX_NORMALIZED_APPLICATION = 8 * 1024 * 1024
APPLICATION_COLUMNS = ("APP_ID", "DECISION_BEFORE", "DECISION_AFTER", "HAS_DIFF", "DECISION_CHANGED")


def find_responses(dataset, directory):
    """Индексирует ответы среза по идентификаторам заявок."""
    root = (dataset / directory).resolve()
    if not root.is_relative_to(dataset) or not root.is_dir():
        raise ValueError(f"Некорректный каталог среза: {directory}")
    files = {}
    for file in root.glob("*.json"):
        if file.is_symlink():
            raise ValueError(f"Символьная ссылка вместо ответа: {file.name}")
        if file.is_file():
            files[file.stem] = file
    return files


def slice_fields(attributes):
    """Переносит поля разреза из нормализованного среза, сохраняя исходное отсутствие."""
    fields = {}
    for (path, array_key), value in attributes.items():
        if path in SEGMENT_FIELDS and value is not None:
            fields.setdefault(SEGMENT_FIELDS[path], {})[array_key] = scalar(value)
    return fields


def reject_constant(value):
    """Отклоняет значения NaN и Infinity, которые не входят в JSON."""
    raise ValueError(f"Недопустимое числовое значение {value}")


def read_normalized_lines(source):
    """Читает строки с ограничением до разбора JSON."""
    while line := source.readline(MAX_NORMALIZED_LINE + 1):
        if len(line) > MAX_NORMALIZED_LINE:
            raise ValueError("Слишком длинная строка нормализации")
        yield json.loads(line, parse_float=Decimal, parse_constant=reject_constant), len(line)


def normalized_rows(file, app_ids):
    """Читает упорядоченные группы и сверяет их с набором и контрактом."""
    with file.open("rb") as source:
        groups = groupby(read_normalized_lines(source), lambda item: item[0]["appId"])
        for expected, (app_id, group) in zip(sorted(app_ids), groups, strict=True):
            if app_id != expected:
                raise ValueError(f"Ожидалась заявка {expected}, получена {app_id}")
            slices = {"BEFORE": {}, "AFTER": {}}
            paths = {}
            size = 0
            for row, length in group:
                size += length
                path = row["path"]
                array_key = row["arrayKey"]
                value = row["value"]
                if (size > MAX_NORMALIZED_APPLICATION or not isinstance(path, list) or not path
                        or not all(isinstance(part, str) and part for part in path)
                        or not isinstance(array_key, str) or isinstance(value, (dict, list))):
                    raise ValueError(f"Некорректная строка нормализации заявки {app_id}")
                texts = [*path, array_key, *([value] if isinstance(value, str) else [])]
                for text in texts:
                    if "\0" in text:
                        raise ValueError(f"Нулевой символ в строке заявки {app_id}")
                    text.encode("utf-8")
                attributes = slices[row["source"]]
                key = tuple(path), array_key
                flat = ".".join(path), array_key
                if paths.setdefault(flat, key) != key:
                    raise ValueError(f"Неоднозначный PATH нормализации заявки {app_id}")
                if key in attributes:
                    raise ValueError(f"Повтор атрибута нормализации заявки {app_id}")
                attributes[key] = value
            if any(attributes.get(DECISION) not in ("APPROVE", "REJECT") for attributes in slices.values()):
                raise ValueError(f"В нормализации заявки {app_id} отсутствует решение")
            yield app_id, slices["BEFORE"], slices["AFTER"]


def dataset_inputs(dataset):
    """Читает манифест и проверяет наличие обеих сторон каждой заявки."""
    dataset = dataset.resolve()
    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8-sig"))
    before = find_responses(dataset, manifest["srez"]["BEFORE"]["dir"])
    after = find_responses(dataset, manifest["srez"]["AFTER"]["dir"])
    if before.keys() != after.keys():
        missing_before = sorted(after.keys() - before.keys())
        missing_after = sorted(before.keys() - after.keys())
        raise ValueError(f"Нет пары для заявок: BEFORE {missing_before[:5]}, AFTER {missing_after[:5]}")
    if type(manifest["applications"]) is not int or len(before) != manifest["applications"]:
        raise ValueError(f"Число пар ({len(before)}) расходится с манифестом")
    return manifest, before, after


def analysis_value(fields, path, value):
    """Группирует числовой лист по верхним границам, сохраняя исходное значение отдельно."""
    buckets = fields.get(path, ())
    if path not in fields:
        return scalar(value)
    if not isinstance(value, bool) and isinstance(value, (int, Decimal)):
        for bucket in buckets:
            if bucket["upper"] is None or value <= Decimal(bucket["upper"]):
                return "Бакет: " + bucket["label"]
    return "Значение: " + scalar(value)


def compare_dataset(dataset, output, normalized=None, rules=None, fields=None):
    """Проверяет комплектность датасета и публикует результаты после полного прогона."""
    if normalized is not None and rules is not None:
        raise ValueError("Табличные правила и готовые нормализованные строки несовместимы")
    manifest, before, after = dataset_inputs(dataset)
    if output.exists() or output.is_symlink():
        raise ValueError(f"Каталог результатов уже существует: {output}")

    summary = {
        "run_id": manifest["run_id"],
        "total_applications": len(before),
        "applications_with_differences": 0,
        "applications_with_decision_changes": 0,
    }
    diff_flag_index = COLUMNS.index("DIFF_FLAG")
    with TemporaryDirectory(dir=output.parent) as temp:
        result = Path(temp) / "result"
        result.mkdir()
        with (result / "comparison.csv").open("w", encoding="utf-8", newline="") as comparison_file, \
                (result / "applications.csv").open("w", encoding="utf-8", newline="") as applications_file, \
                (result / "segments.csv").open("w", encoding="utf-8", newline="") as segments_file:
            comparison = csv.writer(comparison_file, delimiter=";")
            applications = csv.writer(applications_file, delimiter=";")
            segments = csv.DictWriter(segments_file, SEGMENT_COLUMNS, delimiter=";")
            segments.writeheader()
            comparison.writerow(COLUMNS)
            applications.writerow(APPLICATION_COLUMNS)
            pairs = ((app_id, read_response(before[app_id], rules), read_response(after[app_id], rules))
                     for app_id in sorted(before)) if normalized is None else normalized_rows(normalized, before)
            for app_id, old, new in pairs:
                for attributes, file in ((old, before[app_id]), (new, after[app_id])):
                    if attributes.get(DECISION) not in ("APPROVE", "REJECT"):
                        raise ValueError(f"{file}: некорректное поле decisionResult.decision")
                rows = list(compare_responses(app_id, old, new,
                             adjust=lambda path, value: analysis_value(fields or {}, path, value)))
                comparison.writerows(rows)
                has_diff = any(row[diff_flag_index] for row in rows)
                decision_changed = old[DECISION] != new[DECISION]
                application = dict(zip(APPLICATION_COLUMNS, (
                    app_id, old[DECISION], new[DECISION], int(has_diff), int(decision_changed),
                )))
                applications.writerow(application.values())
                segments.writerow(segment(application, {"BEFORE": slice_fields(old), "AFTER": slice_fields(new)}))
                summary["applications_with_differences"] += has_diff
                summary["applications_with_decision_changes"] += decision_changed

        report = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
        (result / "summary.json").write_text(report, encoding="utf-8")
        result.rename(output)
    return report


def main():
    """Принимает каталог датасета и путь к новому каталогу результатов."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--validate-normalized", action="store_true", help="проверить файл строк без сравнения")
    args = parser.parse_args()
    try:
        if args.validate_normalized:
            _, before, _ = dataset_inputs(args.dataset)
            for _ in normalized_rows(args.output, before):
                pass
            return
        normalized = os.environ.get("BALANCE_NORMALIZED_PATH")
        rule_file = os.environ.get("BALANCE_NORMALIZATION_RULES")
        rules = None
        if rule_file:
            entries = json.loads(Path(rule_file).read_text(encoding="utf-8"))
            rules = compile_rules(entries)
        field_file = os.environ.get("BALANCE_ANALYSIS_FIELDS")
        fields = None
        if field_file:
            entries = json.loads(Path(field_file).read_text(encoding="utf-8"), parse_float=Decimal)
            fields = {tuple(entry["path"]): entry["buckets"] for entry in entries}
        report = compare_dataset(args.dataset, args.output, Path(normalized) if normalized else None, rules, fields)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Ошибка обработки датасета: {error}\n")
    print(report, end="")


if __name__ == "__main__":
    main()

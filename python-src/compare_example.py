"""Строит CSV сравнения для учебной пары ответов организаторов."""

import argparse
import csv
import json
import sys
from decimal import Decimal
from pathlib import Path


SCORECARDS = ("company", "scoreCardResult")
LIMIT = ("company", "limitResult")
REASONS = ("company", "rejectReasons")
EXCLUDED = ("company", "decisionResult", "calculatedAt")
COLUMNS = (
    "APP_ID", "PREFIX", "CLASS", "SUFFIX", "ARRAY_KEY", "KEY", "PATH",
    "VALUE_BEFORE", "VALUE_AFTER", "ADJUSTED_BEFORE", "ADJUSTED_AFTER",
    "DIFF_FLAG", "CHANGE_TYPE",
)


def normalize(node, path=(), array_key=""):
    """Возвращает пути и значения листьев по правилам учебного формата."""
    if path == EXCLUDED:
        return
    if path == SCORECARDS:
        for card in node:
            yield from normalize(card, (*path, "scoreCard"), card["name"])
    elif path == REASONS:
        for reason in node:
            yield (*path, "reason", "code"), reason, reason
    elif isinstance(node, dict):
        prefix = (*path, "Limit") if path == LIMIT else path
        for key, value in node.items():
            yield from normalize(value, (*prefix, key), array_key)
    elif isinstance(node, list):
        raise ValueError(f"Не задано правило для массива {'.'.join(path)}")
    else:
        yield path, array_key, node


def scalar(value):
    """Форматирует значение атрибута для CSV."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def adjusted(path, value):
    """Применяет интервалы, показанные в учебной таблице."""
    if path == (*SCORECARDS, "scoreCard", "value"):
        bounds = ((Decimal("0.3"), "(0 - 0.3]"), (Decimal("0.6"), "(0.3 - 0.6]"),
                  (Decimal("1"), "(0.6 - 1]"))
    elif path == (*LIMIT, "Limit", "limit"):
        bounds = ((100000, "(0 - 100K]"), (250000, "(100K - 250K]"))
    else:
        return scalar(value)
    if type(value) in (int, Decimal):
        for upper, label in bounds:
            if 0 < value <= upper:
                return label
    return scalar(value)


def read_slice(file):
    """Загружает один срез и проверяет уникальность ключей его атрибутов."""
    document = json.loads(file.read_text(encoding="utf-8-sig"), parse_float=Decimal)
    attributes = {}
    for path, array_key, value in normalize(document):
        key = path, array_key
        if key in attributes:
            raise ValueError(f"Дубликат атрибута {'.'.join(path)} ({array_key}) в {file}")
        attributes[key] = value
    return attributes


def compare(app_id, before, after, adjust=adjusted):
    """Сопоставляет строки срезов по пути и ключу элемента массива."""
    for path, array_key in dict.fromkeys((*before, *after)):
        key = path, array_key
        old = before.get(key)
        new = after.get(key)
        if key not in before:
            change = "ATTRIBUTE_NEW"
        elif key not in after:
            change = "ATTRIBUTE_LOST"
        elif scalar(old) != scalar(new) or isinstance(old, str) != isinstance(new, str):
            change = "VALUE_CHANGED"
        else:
            change = "SAME"

        yield (
            app_id, path[0], path[1] if len(path) > 2 else "", ".".join(path[2:-1]),
            array_key, path[-1], ".".join(path),
            scalar(old) if key in before else "", scalar(new) if key in after else "",
            adjust(path, old) if key in before else "",
            adjust(path, new) if key in after else "",
            int(change != "SAME"), change,
        )


def main():
    """Читает два JSON-файла и выводит результат сравнения в stdout."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--app-id", default="1001")
    args = parser.parse_args()

    try:
        before = read_slice(args.before)
        after = read_slice(args.after)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Ошибка разбора: {error}\n")

    writer = csv.writer(sys.stdout, delimiter=";")
    writer.writerow(COLUMNS)
    writer.writerows(compare(args.app_id, before, after))


if __name__ == "__main__":
    main()

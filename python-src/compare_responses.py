"""Сравнивает два JSON-ответа СПР из датасета организаторов."""

import argparse
import csv
import json
import sys
from decimal import Decimal
from pathlib import Path

from compare_example import COLUMNS, compare, scalar


EXCLUDED = {"appData.request_id", "appData.createDateTime"}
# None обозначает вектор, в котором позиция элемента значима.
ARRAY_KEYS = {
    "decisionResult.available_offers": ("product_id", "type"),
    "decisionResult.product_trials": ("product_id",),
    "decisionResult.product_trials.rules": ("name",),
    "decisionResult.product_trials.scoring.scoreModels": ("modelName",),
    "decisionResult.scoring": ("modelName",),
    "decisionResult.scoring.value": None,
    "ext_data": ("body.action",),
    "product_catalog": ("mfo_id", "product_id"),
    "tariff_matrix": ("mfo_id", "product_id"),
}


def element_key(item, fields, index, path):
    """Строит ключ элемента массива по заданным полям или позиции."""
    if fields is None:
        return str(index)

    parts = []
    for field in fields:
        value = item
        for segment in field.split("."):
            if not isinstance(value, dict) or segment not in value:
                raise ValueError(f"{path}[{index}]: отсутствует поле ключа {field}")
            value = value[segment]
        if value is None or isinstance(value, (dict, list)) or value == "":
            raise ValueError(f"{path}[{index}]: недопустимое значение ключа {field}")
        # Экранируем разделители составных и вложенных ключей.
        parts.append(scalar(value).replace("%", "%25").replace("|", "%7C").replace("+", "%2B"))
    return "+".join(parts)


def convert_value(value, kind, path):
    """Приводит значение листа к указанному типу или сообщает ошибку поля."""
    if value is None or kind == "keep":
        return value
    if kind == "string":
        return scalar(value)
    if kind == "number" and not isinstance(value, bool):
        try:
            number = Decimal(str(value))
            if number.is_finite():
                if not number:
                    return Decimal(0)
                sign, digits, exponent = number.as_tuple()
                end = len(digits)
                while digits[end - 1] == 0:
                    end -= 1
                return Decimal((sign, digits[:end], exponent + len(digits) - end))
        except ArithmeticError:
            pass
    if kind == "boolean":
        if isinstance(value, bool):
            return value
        if isinstance(value, str) and value.lower() in ("true", "false"):
            return value.lower() == "true"
    raise ValueError(f"{path}: значение не приводится к типу {kind}")


def normalize(node, path=(), keys=(), rules=None, inside_array=False, targets=None):
    """Обходит листья ответа, сохраняя ключи всех массивов на пути."""
    name = ".".join(path)
    rule = rules.get(path) if rules and not inside_array else None
    if (rule is None and name in EXCLUDED) or (rule and rule["excluded"]):
        return
    if isinstance(node, dict):
        if rule:
            raise ValueError(f"{name}: правило требует лист или массив")
        for field, value in node.items():
            yield from normalize(value, (*path, field), keys, rules, targets=targets)
    elif isinstance(node, list):
        configured = rule and rule["arrayKeys"]
        if rule and not configured:
            raise ValueError(f"{name}: для массива укажите поля ключа")
        if configured and len(keys) >= 2:
            raise ValueError(f"{name}: ключ настраивается только для первых двух уровней массивов")
        if not configured and name not in ARRAY_KEYS:
            raise ValueError(f"{name}: не задано правило для массива")
        fields = configured or ARRAY_KEYS[name]
        seen = set()
        for index, item in enumerate(node):
            key = element_key(item, fields, index, name)
            if key in seen:
                raise ValueError(f"{name}[{index}]: дубликат ключа {key}")
            seen.add(key)
            yield from normalize(item, path, (*keys, key), rules, inside_array=True, targets=targets)
    else:
        if targets and path in targets and targets[path] != path:
            raise ValueError(f"{name}: исходное поле совпадает с путём результата другого правила")
        if rule:
            if rule["arrayKeys"]:
                raise ValueError(f"{name}: ожидается массив")
            value = convert_value(node, rule["type"], name)
            path = tuple(rule["targetPath"]) or path
        yield path, "|".join(keys), value if rule else node


def compile_rules(entries):
    """Проверяет уникальность исходных и целевых путей до чтения срезов."""
    rules, targets = {}, {}
    for rule in entries:
        source = tuple(rule["sourcePath"])
        target = tuple(rule["targetPath"])
        if source in rules:
            raise ValueError(f"{'.'.join(source)}: повтор правила")
        rules[source] = rule
        if target:
            if target in targets:
                raise ValueError(f"{'.'.join(target)}: повтор пути результата")
            targets[target] = source
    return rules, targets


def normalize_document(document, rules=None):
    """Проверяет корень и возвращает уникальные атрибуты нормализованного ответа."""
    if not isinstance(document, dict):
        raise ValueError("корень ответа должен быть JSON-объектом")
    attributes = {}
    entries, targets = rules if rules is not None else (None, None)
    for path, array_key, value in normalize(document, rules=entries, targets=targets):
        key = path, array_key
        if key in attributes:
            raise ValueError(f"{'.'.join(path)}: дубликат атрибута ({array_key})")
        attributes[key] = value
    return attributes


def read_response(file, rules=None):
    """Читает один ответ и проверяет уникальность атрибутов."""
    try:
        document = json.loads(file.read_text(encoding="utf-8-sig"), parse_float=Decimal)
        return normalize_document(document, rules)
    except (OSError, ValueError) as error:
        raise ValueError(f"{file}: {error}") from error


def main():
    """Принимает два одноимённых JSON и выводит CSV в stdout."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args()

    if args.before.name != args.after.name or args.before.suffix.lower() != ".json":
        parser.error("нужны два одноимённых JSON-файла одной заявки")

    try:
        before = read_response(args.before)
        after = read_response(args.after)
    except ValueError as error:
        parser.exit(1, f"Ошибка разбора: {error}\n")

    writer = csv.writer(sys.stdout, delimiter=";")
    writer.writerow(COLUMNS)
    writer.writerows(compare_responses(args.before.stem, before, after))


def compare_responses(app_id, before, after, adjust=None):
    """Сопоставляет реальные ответы и при необходимости группирует значения."""
    return compare(app_id, before, after, adjust=adjust or (lambda _path, value: scalar(value)))


if __name__ == "__main__":
    main()

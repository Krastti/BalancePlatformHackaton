"""Исполняет декларативные проверки над тем же MartAPI, что и шаблоны."""

from decimal import Decimal, InvalidOperation


def numeric(value):
    """Возвращает конечное число для сравнения, если лист читается как число."""
    try:
        number = Decimal(value)
        return number if number.is_finite() else None
    except (InvalidOperation, TypeError, ValueError):
        return None


def evaluate_rule(mart, check):
    """Формирует одну строку результата с метрикой и уникальными заявками."""
    rule = check["rule"]
    kind, path, side = rule["type"], rule["path"], rule["source"]
    maximum = Decimal(rule["max"]) if kind != "change" else None
    minimum = Decimal(rule["min"]) if kind == "range" else None
    rows = mart.rows(path=path)
    eligible, affected = set(), set()
    for row in rows:
        app = row["APP_ID"]
        change = row["CHANGE_TYPE"]
        if kind == "change":
            eligible.add(app)
            if change != "SAME":
                affected.add(app)
            continue
        if change == ("ATTRIBUTE_NEW" if side == "BEFORE" else "ATTRIBUTE_LOST"):
            continue
        if kind == "share":
            affected.add(app)
            continue
        value = numeric(row[f"VALUE_{side}"])
        if value is None:
            continue
        eligible.add(app)
        if kind == "threshold" and value > maximum:
            affected.add(app)
        elif kind == "range" and not minimum <= value <= maximum:
            affected.add(app)

    total = mart.total_apps() if kind == "share" else len(eligible)
    if not total:
        return []
    share = Decimal(len(affected)) / total
    if kind == "share":
        numerator, denominator = maximum.as_integer_ratio()
        failed = len(affected) * denominator > numerator * total
    else:
        failed = bool(affected)
    condition = {
        "threshold": f"значение {side} > {maximum}",
        "range": f"значение {side} вне диапазона [{minimum}; {maximum}]",
        "share": f"доля изменившихся заявок > {maximum}",
        "change": "есть изменение значения атрибута",
    }[kind]
    actual = f"{len(affected)} из {total} ({share * 100:.1f}%)"
    if kind in ("threshold", "range"):
        actual = f"нарушений: {len(affected)} из {total}"
    return [{"LABEL": rule["label"], "ARGUMENT": path, "CONDITION": condition, "ACTUAL": actual,
             "SOURCE": side, "RESULT": "FAIL" if failed else "PASS",
             "CONFIDENCE": "LOW" if 0 < len(affected) < 5 else "HIGH", "CNT": len(affected), "TOTAL": total,
             "METRIC": float(share), "APP_IDS": sorted(affected)[:100]}]

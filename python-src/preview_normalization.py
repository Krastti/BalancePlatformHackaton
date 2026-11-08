"""Показывает ограниченную выборку листьев пары после табличного приведения."""

import json
import sys
from decimal import Decimal

from compare_dataset import DECISION, analysis_value
from compare_responses import compile_rules, normalize_document, scalar


def main():
    """Применяет правила из stdin и возвращает таблицу или диагностику."""
    payload = json.load(sys.stdin)
    try:
        rules = compile_rules(payload["rules"])
        selected = {tuple(rule["targetPath"] or rule["sourcePath"]) for rule in payload["rules"]}
        fields = {tuple(field["path"]): field for field in payload.get("fields", [])}
        buckets = {path: field["buckets"] for path, field in fields.items()}
        sides = []
        for side in ("before", "after"):
            document = json.loads(payload[side], parse_float=Decimal)
            attributes = normalize_document(document, rules)
            if attributes.get(DECISION) not in ("APPROVE", "REJECT"):
                raise ValueError(f"{side.upper()}: некорректное поле decisionResult.decision")
            candidates = (item for item in attributes if not fields or item[0] in fields)
            ordered = sorted(candidates, key=lambda item: (not any(
                item[0][:len(prefix)] == prefix for prefix in selected), item))
            sides.append([{"path": ".".join(path)[:240], "key": key[:160],
                           "value": scalar(attributes[path, key])[:160],
                           "label": fields.get(path, {}).get("label", ""),
                           "group": analysis_value(buckets, path, attributes[path, key])[:160]}
                          for path, key in ordered[:25]])
        print(json.dumps({"before": sides[0], "after": sides[1]}, ensure_ascii=False))
    except (ValueError, KeyError, TypeError) as error:
        print(json.dumps({"error": str(error)[:400]}, ensure_ascii=False))


if __name__ == "__main__":
    main()

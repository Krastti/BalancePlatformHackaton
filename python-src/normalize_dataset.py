"""Пишет типизированные строки двух срезов в JSON Lines."""

import argparse
import json
from decimal import Decimal
from pathlib import Path

from compare_dataset import dataset_inputs
from compare_responses import read_response


def normalize_dataset(dataset, output):
    """Выводит группы заявок по порядку APP_ID, сохраняя JSON-типы значений."""
    _, before, after = dataset_inputs(dataset)
    slices = {"BEFORE": before, "AFTER": after}
    with output.open("x", encoding="utf-8") as target:
        for app_id in sorted(slices["BEFORE"]):
            for side, files in slices.items():
                for (path, array_key), value in read_response(files[app_id]).items():
                    row = {"appId": app_id, "source": side, "path": path, "arrayKey": array_key}
                    encoded = str(value) if isinstance(value, Decimal) else json.dumps(value, ensure_ascii=False)
                    target.write(json.dumps(row, ensure_ascii=False)[:-1] + ', "value": ' + encoded + "}\n")


def main():
    """Принимает каталог датасета и путь к новому файлу строк."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        normalize_dataset(args.dataset, args.output)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Ошибка нормализации: {error}\n")


if __name__ == "__main__":
    main()

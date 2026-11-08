"""Готовит вхождения изменившихся атрибутов для сводной."""

import csv
import sys
from pathlib import Path


def main(comparison, output):
    """Возвращает по одному вхождению для каждого существующего среза отличия."""
    with (comparison / "comparison.csv").open(encoding="utf-8", newline="") as source:
        with output.open("x", encoding="utf-8", newline="") as target:
            writer = csv.writer(target, delimiter=";")
            writer.writerow(("APP_ID", "PATH", "ARRAY_KEY", "SOURCE"))
            for row in csv.DictReader(source, delimiter=";"):
                change = row["CHANGE_TYPE"]
                if change == "SAME":
                    continue
                key = (row["APP_ID"], row["PATH"], row["ARRAY_KEY"])
                if change != "ATTRIBUTE_NEW":
                    writer.writerow((*key, "BEFORE"))
                if change != "ATTRIBUTE_LOST":
                    writer.writerow((*key, "AFTER"))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))

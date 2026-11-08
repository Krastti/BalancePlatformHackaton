"""Запускает доверенные локальные проверки на CSV-витрине."""

import argparse
import csv
import contextlib
import math
import os
import sys
import json
from pathlib import Path
from runpy import run_path
from types import ModuleType

from csv_mart import CsvMart
from configured_checks import evaluate_rule

# Наблюдаемые изменения сами по себе не являются нарушением критерия.
SIGNAL_ONLY_CHECKS = {"attribute_new_loss", "target_model_changed"}
SIGNAL_ARGUMENTS = {"external_score_drift": {"characteristics.score_okb"}}


def validate_rows(rows):
    """Отклоняет строки вне контракта до публикации всего отчёта."""
    if not isinstance(rows, list):
        raise ValueError("Проверка должна вернуть список строк")
    for number, row in enumerate(rows, start=1):
        if not isinstance(row, dict):
            raise ValueError(f"Строка {number} должна быть словарём")
        if (
            not all(isinstance(row.get(key), str) for key in ("LABEL", "ARGUMENT"))
            or not all(isinstance(row.get(key, ""), str) for key in ("CONDITION", "ACTUAL"))
            or row.get("SOURCE") not in ("BEFORE", "AFTER")
            or row.get("RESULT") not in ("PASS", "FAIL", "SIGNAL")
            or row.get("CONFIDENCE") not in ("HIGH", "LOW")
            or not all(type(row.get(key)) is int and row[key] >= 0 for key in ("CNT", "TOTAL"))
            or type(row.get("METRIC")) not in (int, float)
            or not math.isfinite(row["METRIC"])
            or not isinstance(row.get("APP_IDS"), list)
            or not all(isinstance(app_id, str) and app_id for app_id in row["APP_IDS"])
        ):
            raise ValueError(f"Строка {number} не соответствует контракту результата")


def load_check(path, original=None):
    """Исполняет неизменяемую копию с прежним именем и контекстом файла."""
    if original is None:
        return run_path(str(path))["CHECK"]

    name = "<run_path>"
    module = ModuleType(name)
    module.__file__ = original
    module.__package__ = ""
    previous = sys.modules.get(name)
    previous_arg = sys.argv[0]
    sys.modules[name] = module
    sys.argv[0] = original
    try:
        exec(compile(path.read_bytes(), original, "exec"), module.__dict__)
        return module.CHECK
    finally:
        sys.argv[0] = previous_arg
        if previous is None:
            del sys.modules[name]
        else:
            sys.modules[name] = previous


def run_checks(mart, paths, originals=None, parameters=None):
    """Объединяет строки проверок и сохраняет отдельные ошибки каждого скрипта."""
    report = {"checks": [], "rows": []}
    originals = originals or {}
    parameters = parameters or {}
    seen = set()
    for path in paths:
        check_id = None
        check_label = path.stem
        accepted_id = False
        try:
            with contextlib.redirect_stdout(sys.stderr):
                check = load_check(path, originals.get(str(path)))
            check_id = check["id"]
            if not isinstance(check_id, str) or not check_id or check_id in seen:
                raise ValueError(f"Недопустимый или повторяющийся ID проверки: {check_id!r}")
            seen.add(check_id)
            accepted_id = True
            if not isinstance(check["label"], str) or not check["label"]:
                raise ValueError("Название проверки должно быть непустой строкой")
            check_label = check["label"]
            with contextlib.redirect_stdout(sys.stderr):
                rows = check["entrypoint"](mart, {**check["params"], **parameters.get(check_id, {})})
            for row in rows:
                if not isinstance(row, dict) or row.get("RESULT") != "FAIL":
                    continue
                argument = row.get("ARGUMENT")
                if check_id in SIGNAL_ONLY_CHECKS or (
                    isinstance(argument, str)
                    and argument in SIGNAL_ARGUMENTS.get(check_id, set())
                ):
                    row["RESULT"] = "SIGNAL"
            validate_rows(rows)
            report["checks"].append({"id": check_id, "label": check["label"], "script": path.name,
                                     "row_count": len(rows)})
            report["rows"].extend(rows)
        except Exception as error:
            error_id = check_id if accepted_id else f"error:{path.name}"
            report["checks"].append({"id": error_id, "label": check_label, "script": path.name, "row_count": 0,
                                     "status": "ERROR", "message": f"{path.name}: {error}"[:1000]})
    return report


def run_configured_checks(mart, paths, plan, originals, parameters):
    """Выполняет шаблоны и правила в порядке сохранённого плана."""
    by_name = {Path(originals.get(str(path), str(path))).name: path for path in paths}
    report = {"checks": [], "rows": []}
    seen = set()
    for item in plan:
        if not item["enabled"] or (item.get("script") or {}).get("kind") == "inline":
            continue
        rows = []
        try:
            if item.get("rule") is not None:
                rows = evaluate_rule(mart, item)
                validate_rows(rows)
                checks = [{"id": item["id"], "label": item["rule"]["label"], "row_count": len(rows)}]
                if not rows:
                    checks[0]["status"] = "NO_DATA"
            else:
                path = by_name[item["script"]["file"]]
                result = run_checks(mart, [path], originals, parameters)
                checks, rows = result["checks"], result["rows"]
                if checks and checks[0].get("status") == "ERROR":
                    checks[0]["id"] = item["id"]
        except Exception as error:
            label = (item.get("rule") or {}).get("label") or (item.get("script") or {}).get("file") or item["id"]
            checks = [{"id": item["id"], "label": label, "row_count": 0,
                       "status": "ERROR", "message": str(error)[:1000]}]
            rows = []
        for check in checks:
            if check["id"] in seen:
                raise ValueError(f"Повторяющийся ID проверки: {check['id']}")
            seen.add(check["id"])
        report["checks"].extend(checks)
        report["rows"].extend(rows)
    return report


def main():
    """Сохраняет прежний формат одиночной проверки и добавляет общий отчёт."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="каталог с applications.csv и comparison.csv")
    parser.add_argument("checks", nargs="*", type=Path, help="локальные файлы проверок")
    parser.add_argument("--report", action="store_true", help="включить список выполненных проверок")
    args = parser.parse_intermixed_args()

    try:
        originals = json.loads(os.environ.get("BALANCE_CHECK_SNAPSHOT_MAP", "{}"))
        parameters = json.loads(os.environ.get("BALANCE_RUN_PARAMETERS", "{}")).get("checks", {})
        plan_file = os.environ.get("BALANCE_CONFIGURED_CHECKS")
        mart = CsvMart(args.results)
        if plan_file:
            plan = json.loads(Path(plan_file).read_text(encoding="utf-8"))
            report = run_configured_checks(mart, args.checks, plan, originals, parameters)
        else:
            if not args.checks:
                raise ValueError("Не указаны файлы проверок")
            report = run_checks(mart, args.checks, originals, parameters)
        if not args.report:
            errors = [check["message"] for check in report["checks"] if check.get("status") == "ERROR"]
            if errors:
                raise ValueError("; ".join(errors))
        result = report if args.report or len(args.checks) > 1 else report["rows"]
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    except (OSError, ValueError, csv.Error, KeyError, TypeError) as error:
        parser.exit(1, f"Ошибка запуска проверки: {error}\n")


if __name__ == "__main__":
    main()

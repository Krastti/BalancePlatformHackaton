"""Читает или проверяет Python без исполнения пользовательского кода."""

import ast
import codecs
import io
import json
import re
import sys
import tokenize


def source_text(raw):
    """Декодирует файл по правилам Python и готовит копию для записи в UTF-8."""
    encoding, _ = tokenize.detect_encoding(io.BytesIO(raw).readline)
    text = raw.decode(encoding)
    if codecs.lookup(encoding).name in ("utf-8", "utf-8-sig"):
        return text
    lines = text.splitlines(keepends=True)
    for index, line in enumerate(lines[:2]):
        if index == 1 and lines[0].strip() and not lines[0].lstrip().startswith("#"):
            break
        lines[index], changed = re.subn(r"^([ \t\f]*#.*?coding[:=][ \t]*)([-\w.]+)", r"\g<1>utf-8", line, count=1)
        if changed:
            break
    return "".join(lines)


def inspect(raw, mode):
    """Проверяет синтаксис и отмечает видимое объявление CHECK."""
    if mode == "copy":
        return {"code": source_text(raw)}
    encoding, _ = tokenize.detect_encoding(io.BytesIO(raw).readline)
    if codecs.lookup(encoding).name not in ("utf-8", "utf-8-sig"):
        return {"valid": False, "message": "В редакторе допускается только UTF-8"}
    compile(raw, "<редактор>", "exec")
    tree = compile(raw, "<редактор>", "exec", ast.PyCF_ONLY_AST)
    check = any(isinstance(node, ast.Name) and node.id == "CHECK" and isinstance(node.ctx, ast.Store)
                for node in ast.walk(tree))
    return {"valid": True, "declaresCheck": check}


def main():
    """Возвращает результат статической проверки или диагностическую строку."""
    try:
        print(json.dumps(inspect(sys.stdin.buffer.read(), sys.argv[1]), ensure_ascii=False))
    except SyntaxError as error:
        print(json.dumps({"valid": False, "message": error.msg, "line": error.lineno}, ensure_ascii=False))
    except (UnicodeError, LookupError, ValueError):
        print(json.dumps({"valid": False, "message": "Не удалось декодировать исходный код"}, ensure_ascii=False))


if __name__ == "__main__":
    main()

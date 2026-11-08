"""Переносит только ожидаемый результат из изолированного процесса."""

import ctypes
import errno
import os
import resource
import stat
import subprocess
import sys
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile


WORK = Path("/work")
RESULT = WORK / "result"
REQUIRED = ("summary.json", "applications.csv", "comparison.csv")
MAX_FILE = 128 * 1024 * 1024


def regular(path):
    """Проверяет допустимый размер обычного файла без перехода по ссылке."""
    try:
        info = path.lstat()
    except FileNotFoundError:
        return False
    return stat.S_ISREG(info.st_mode) and info.st_size <= MAX_FILE


def restrict_child():
    """Запрещает дочернему скрипту создавать процессы и вмешиваться в упаковщик."""
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))
    seccomp = ctypes.CDLL("libseccomp.so.2")
    seccomp.seccomp_init.argtypes = [ctypes.c_uint32]
    seccomp.seccomp_init.restype = ctypes.c_void_p
    seccomp.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    seccomp.seccomp_load.argtypes = [ctypes.c_void_p]
    seccomp.seccomp_release.argtypes = [ctypes.c_void_p]
    seccomp.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    allow = 0x7FFF0000
    deny = 0x00050000 | errno.EPERM
    policy = seccomp.seccomp_init(allow)
    if not policy:
        raise RuntimeError("Не удалось создать фильтр процессов")
    try:
        for name in (b"clone", b"clone3", b"fork", b"vfork", b"ptrace", b"process_vm_readv",
                     b"process_vm_writev", b"pidfd_getfd", b"kill", b"tkill", b"tgkill", b"pidfd_send_signal",
                     b"memfd_create", b"memfd_secret", b"shmget", b"semget", b"msgget", b"mq_open",
                     b"keyctl", b"add_key", b"request_key", b"io_uring_setup", b"userfaultfd"):
            number = seccomp.seccomp_syscall_resolve_name(name)
            if number >= 0 and seccomp.seccomp_rule_add(policy, deny, number, 0) != 0:
                raise RuntimeError("Не удалось добавить ограничение процессов")
        if seccomp.seccomp_load(policy) != 0:
            raise RuntimeError("Не удалось применить ограничение процессов")
    finally:
        seccomp.seccomp_release(policy)


def run(mode):
    """Выполняет код в дочернем процессе и отправляет артефакты штатному импорту."""
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_AS, (768 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_NOFILE, (128, 128))
    if mode == "compare":
        subprocess.run([sys.executable, "-B", "/code/inline.py", "/data", str(RESULT)],
                       stdin=subprocess.DEVNULL, stdout=sys.stderr, check=True, preexec_fn=restrict_child)
        if RESULT.is_symlink() or not RESULT.is_dir() or not all(regular(RESULT / name) for name in REQUIRED):
            raise ValueError("Процедура не создала обязательные файлы")
        names = list(REQUIRED)
        if (RESULT / "segments.csv").exists():
            if not regular(RESULT / "segments.csv"):
                raise ValueError("Некорректный файл разрезов")
            names.append("segments.csv")
    elif mode in ("normalize", "facts"):
        names = ["normalized.jsonl" if mode == "normalize" else "facts.csv"]
        RESULT.mkdir()
        subprocess.run([sys.executable, "-B", "/code/inline.py", "/data", str(RESULT / names[0])],
                       stdin=subprocess.DEVNULL, stdout=sys.stderr, check=True, preexec_fn=restrict_child)
        if RESULT.is_symlink() or not regular(RESULT / names[0]):
            raise ValueError("Некорректный файл результата")
    elif mode == "check":
        names = ["checks.json"]
        RESULT.mkdir()
        with (RESULT / "checks.json").open("xb") as report:
            subprocess.run([sys.executable, "-B", "/runner/run_check.py", "/data", "--report", "/code/inline.py"],
                           stdin=subprocess.DEVNULL, stdout=report, check=True, preexec_fn=restrict_child)
        if not regular(RESULT / "checks.json"):
            raise ValueError("Некорректный отчёт проверки")
    else:
        raise ValueError("Неизвестный режим")

    with ZipFile(sys.stdout.buffer, "w", compression=ZIP_STORED) as archive:
        for name in names:
            fd = os.open(RESULT / name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as source:
                info = os.fstat(source.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE:
                    raise ValueError("Недопустимый файл результата")
                with archive.open(name, "w") as entry:
                    remaining = MAX_FILE
                    while chunk := source.read(65536):
                        remaining -= len(chunk)
                        if remaining < 0:
                            raise ValueError("Слишком большой результат")
                        entry.write(chunk)


if __name__ == "__main__":
    run(sys.argv[1])

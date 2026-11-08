package com.kratti.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Запускает пользовательский Python с отдельными файловой системой, сетью и процессами. */
@Service
public class DatasetPythonSandbox {
    /** Определяет ожидаемый результат изолированного процесса. */
    public enum Mode {
        COMPARE, CHECK, NORMALIZE, FACTS
    }

    private static final Path RUNNER = Path.of("python-src").toAbsolutePath().normalize();
    private static final long MAX_OUTPUT = 192L * 1024 * 1024;
    private final boolean enabled;

    /** Разрешает изоляцию только по настройке оператора. */
    public DatasetPythonSandbox(@Value("${balance.python.sandbox-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** Возвращает состояние настройки исполнения пользовательского кода. */
    public boolean enabled() {
        return enabled;
    }

    /** Строит команду с read-only входом и временным томом, ограниченным по размеру. */
    public ProcessBuilder command(Path input, Path script, Path archive, Mode mode, String parameters, Path normalized) throws IOException {
        if (!enabled) {
            throw new IOException("Изолированный Python отключён");
        }
        List<String> args = new ArrayList<>(List.of("/usr/bin/bwrap",
                "--unshare-all", "--unshare-user", "--disable-userns", "--die-with-parent", "--new-session",
                "--cap-drop", "ALL", "--clearenv", "--setenv", "LANG", "C.UTF-8",
                "--ro-bind", "/usr/lib", "/usr/lib", "--ro-bind-try", "/usr/lib64", "/usr/lib64",
                "--symlink", "/usr/lib", "/lib", "--symlink", "/usr/lib64", "/lib64",
                "--ro-bind", "/usr/bin/python3", "/usr/bin/python3",
                "--dev", "/dev", "--remount-ro", "/dev", "--size", Long.toString(MAX_OUTPUT), "--tmpfs", "/work",
                "--symlink", "/work", "/tmp", "--chdir", "/work",
                "--ro-bind", input.toString(), "/data",
                "--ro-bind", script.toString(), "/code/inline.py"));
        for (String file : List.of("sandbox_entry.py", "run_check.py", "configured_checks.py", "csv_mart.py", "compare_responses.py",
                "compare_example.py", "compare_dataset.py", "normalize_dataset.py")) {
            args.addAll(List.of("--ro-bind", RUNNER.resolve(file).toString(), "/runner/" + file));
        }
        args.addAll(List.of("--setenv", "BALANCE_RUN_PARAMETERS", parameters));
        if (normalized != null) {
            args.addAll(List.of("--ro-bind", normalized.toString(), "/normalized.jsonl",
                    "--setenv", "BALANCE_NORMALIZED_PATH", "/normalized.jsonl"));
        }
        args.addAll(List.of("--remount-ro", "/", "--setenv", "PYTHONPATH", "/runner", "/usr/bin/python3", "-B",
                "/runner/sandbox_entry.py", mode.name().toLowerCase(Locale.ROOT)));
        ProcessBuilder process = new ProcessBuilder(args).redirectOutput(archive.toFile());
        process.environment().clear();
        return process;
    }

    /** Извлекает ожидаемые файлы, ограничивая размер и исключая ссылки и пути из архива. */
    public void extract(Path archive, Path output, Mode mode) throws IOException {
        Set<String> allowed = switch (mode) {
            case CHECK -> Set.of("checks.json");
            case FACTS -> Set.of("facts.csv");
            case NORMALIZE -> Set.of("normalized.jsonl");
            case COMPARE -> Set.of("summary.json", "applications.csv", "comparison.csv", "segments.csv");
        };
        if (Files.size(archive) > MAX_OUTPUT + 65536) {
            throw new IOException("Слишком большой архив результата");
        }
        Files.createDirectory(output);
        long size = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!allowed.contains(entry.getName()) || entry.isDirectory()) {
                    throw new IOException("Неожиданный файл в результате Python");
                }
                try (var target = Files.newOutputStream(output.resolve(entry.getName()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[65536];
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        size += read;
                        if (size > MAX_OUTPUT) {
                            throw new IOException("Слишком большой результат Python");
                        }
                        target.write(buffer, 0, read);
                    }
                }
            }
        }
        Set<String> required = mode == Mode.COMPARE ? Set.of("summary.json", "applications.csv", "comparison.csv") : allowed;
        if (required.stream().anyMatch(name -> !Files.isRegularFile(output.resolve(name)))) {
            throw new IOException("В архиве отсутствуют обязательные файлы");
        }
    }
}

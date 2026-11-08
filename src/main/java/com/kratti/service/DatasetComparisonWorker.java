package com.kratti.service;

import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.AnalysisConfiguration.Check;
import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import com.kratti.dto.response.DatasetConfigurationViews.FrozenFile;
import com.kratti.dto.response.DatasetConfigurationViews.Snapshot;
import com.kratti.dto.response.DatasetComparisonViews.Run;
import com.kratti.dto.response.DatasetComparisonViews.StageName;
import com.kratti.repository.DatasetComparisonImportRepository.InvalidPivotMembershipException;
import com.kratti.repository.DatasetComparisonRepository;
import com.kratti.service.DatasetPythonSandbox.Mode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Обрабатывает устойчивую очередь в фоне и изолирует файлы каждого процесса Python. */
@Component
public class DatasetComparisonWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DatasetComparisonWorker.class);
    private static final int DIAGNOSTIC_LIMIT = 4096;
    private static final String RUN_SNAPSHOT = """
            import sys
            from pathlib import Path
            copy, original = sys.argv[1:3]
            sys.argv = [original, *sys.argv[3:]]
            sys.path[0] = str(Path(original).parent)
            __file__ = original
            exec(compile(Path(copy).read_bytes(), original, "exec"))
            """;

    private final DatasetComparisonRepository queue;
    private final DatasetComparisonService comparisons;
    private final DatasetComparisonImportService importer;
    private final DatasetStageSourceService sources;
    private final DatasetPythonSandbox sandbox;
    private final ObjectMapper json;
    private final String python;
    private final Path script;
    private final Path checkScript;
    private final Path checksDirectory;
    private final boolean snapshotChecks;
    private final Duration timeout;
    private volatile boolean ready;

    /** Настраивает команду Python и конечный таймаут обработки. */
    public DatasetComparisonWorker(DatasetComparisonRepository queue, DatasetComparisonService comparisons,
                                   DatasetComparisonImportService importer, DatasetStageSourceService sources,
                                   DatasetPythonSandbox sandbox, ObjectMapper json,
                                   @Value("${balance.python.executable:python3}") String python,
                                   @Value("${balance.python.script:python-src/compare_dataset.py}") String script,
                                   @Value("${balance.python.check-script:python-src/run_check.py}") String checkScript,
                                   @Value("${balance.checks.path:}") String checksPath,
                                   @Value("${balance.python.check-snapshot-map:false}") boolean useSnapshotMap,
                                   @Value("${balance.python.timeout:PT5M}") Duration timeout) {
        if (timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Таймаут Python должен быть положительным");
        }
        this.queue = queue;
        this.comparisons = comparisons;
        this.importer = importer;
        this.sources = sources;
        this.sandbox = sandbox;
        this.json = json;
        this.python = python;
        this.script = Path.of(script).toAbsolutePath().normalize();
        this.checkScript = Path.of(checkScript).toAbsolutePath().normalize();
        this.snapshotChecks = useSnapshotMap
                || this.checkScript.equals(Path.of("python-src/run_check.py").toAbsolutePath().normalize());
        this.checksDirectory = checksPath.isBlank() ? null : Path.of(checksPath).toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    /** Регистрирует набор и восстанавливает очередь до начала фонового опроса. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() throws IOException {
        comparisons.registerDataset();
        queue.recoverInterrupted();
        ready = true;
    }

    /** Захватывает и обрабатывает одно задание за итерацию. */
    @Scheduled(fixedDelayString = "${balance.worker.poll-delay-ms:1000}")
    public void poll() {
        if (ready) {
            queue.claim().ifPresent(this::execute);
        }
    }

    /** Обновляет статус при ошибке процесса или импорта и удаляет рабочие файлы. */
    private void execute(Run run) {
        Path work = null;
        try {
            Dataset dataset = comparisons.requireDataset(run.datasetId());
            String fingerprint = fingerprintInput(run, run.input().expectedSha256());
            if (fingerprint == null) {
                return;
            }
            String runtime = fingerprintRuntime(run, run.input().expectedRuntimeSha256());
            if (runtime == null) {
                return;
            }
            queue.recordInput(run.id(), fingerprint, runtime);
            work = Files.createTempDirectory("balance-comparison-");
            Path output = work.resolve("result");
            Snapshot snapshot = queue.snapshot(run.id()).orElse(null);
            String parameters = snapshot == null ? "{}" : json.writeValueAsString(snapshot.configuration().parameters());
            if ("null".equals(parameters)) {
                parameters = "{}";
            }
            Path normalized = null;
            if (snapshot != null && (snapshot.normalizer() != null || snapshot.configuration().normalizerCode() != null)) {
                normalized = work.resolve("normalization").resolve("normalized.jsonl");
                if (!normalize(run, normalized, snapshot, parameters) || !validateNormalized(run, normalized)) {
                    return;
                }
                queue.startComparison(run.id());
            }
            String editorProcedure = snapshot == null ? null : snapshot.configuration().procedureCode();
            if (!(editorProcedure == null ? compare(run, output, snapshot == null ? null : snapshot.procedure(),
                    parameters, normalized, snapshot == null ? null : snapshot.analysis()) : isolated(run, output, editorProcedure, false, parameters, normalized))) {
                return;
            }
            boolean factsFirst = snapshot != null && snapshot.analysis() != null && snapshot.analysis().factsFirst();
            if (!factsFirst && !runChecks(run, output, snapshot, parameters)) {
                return;
            }
            Path membership = null;
            if (snapshot != null && (snapshot.factsProcedure() != null || snapshot.configuration().factsCode() != null)) {
                queue.startFacts(run.id());
                membership = work.resolve("facts-result").resolve("facts.csv");
                if (!prepareFacts(run, output, membership, snapshot, parameters)) {
                    return;
                }
            }
            if (factsFirst && !runChecks(run, output, snapshot, parameters)) {
                return;
            }
            if (fingerprintInput(run, fingerprint) == null || fingerprintRuntime(run, runtime) == null) {
                return;
            }
            queue.startImport(run.id());
            try {
                importer.publish(run.id(), dataset, output, membership);
            } catch (InvalidPivotMembershipException error) {
                LOG.warn("Некорректный состав фактов запуска {}", run.id(), error);
                queue.fail(run.id(), "FACTS_RESULT_INVALID", "Файл фактов не соответствует контракту сравнения");
            } catch (IOException | RuntimeException error) {
                LOG.error("Ошибка импорта результатов запуска {}", run.id(), error);
                queue.fail(run.id(), "IMPORT_FAILED", "Не удалось сохранить результаты сравнения");
            }
        } catch (InterruptedException error) {
            try {
                queue.fail(run.id(), "INTERRUPTED", "Обработка сравнения была прервана");
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (IOException | RuntimeException error) {
            LOG.error("Ошибка подготовки сравнения {}", run.id(), error);
            queue.fail(run.id(), "EXECUTION_FAILED", "Не удалось запустить обработку набора");
        } finally {
            cleanup(work);
        }
    }

    /** Проверяет доступность пакета и его соответствие ожидаемому входу. */
    private String fingerprintInput(Run run, String expected) {
        String actual;
        try {
            actual = comparisons.inputFingerprint(run.datasetId());
        } catch (IOException | RuntimeException error) {
            queue.fail(run.id(), expected == null ? "INPUT_UNAVAILABLE" : "INPUT_CHANGED",
                    "Не удалось прочитать ожидаемые данные набора");
            return null;
        }
        if (expected != null && !expected.equals(actual)) {
            queue.recordInput(run.id(), actual, null);
            queue.fail(run.id(), "INPUT_CHANGED", "Данные набора отличаются от ожидаемых");
            return null;
        }
        return actual;
    }

    /** Проверяет доступность штатного Python и его соответствие ожидаемой версии. */
    private String fingerprintRuntime(Run run, String expected) {
        String actual;
        try {
            actual = comparisons.runtimeFingerprint(script);
        } catch (IOException | RuntimeException error) {
            queue.fail(run.id(), expected == null ? "RUNTIME_UNAVAILABLE" : "RUNTIME_CHANGED",
                    "Не удалось прочитать ожидаемый штатный Python");
            return null;
        }
        if (expected != null && !expected.equals(actual)) {
            queue.fail(run.id(), "RUNTIME_CHANGED", "Штатный Python отличается от ожидаемой версии");
            return null;
        }
        return actual;
    }

    /** Запускает выбранные проверки и фиксирует их старт только при наличии проверок. */
    private boolean runChecks(Run run, Path output, Snapshot snapshot, String parameters)
            throws IOException, InterruptedException {
        boolean enabled = snapshot == null ? checksDirectory != null
                : snapshot.analysis() != null ? snapshot.analysis().checks().stream().anyMatch(Check::enabled)
                : !snapshot.files().isEmpty() || snapshot.configuration().checkCode() != null;
        if (!enabled) {
            return true;
        }
        queue.startChecks(run.id());
        return snapshot == null ? check(run, output) : checkFrozen(run, output, snapshot, parameters);
    }

    /** Выполняет выбранную нормализацию и проверяет наличие результата до сравнения. */
    private boolean normalize(Run run, Path output, Snapshot snapshot, String parameters)
            throws IOException, InterruptedException {
        Path work = output.getParent().getParent();
        if (snapshot.normalizer() == null) {
            Path input = Files.writeString(work.resolve("normalize_inline.py"), snapshot.configuration().normalizerCode());
            Path copy = sources.snapshot(run.id(), StageName.NORMALIZATION,
                    work.resolve("normalization-source"), List.of(input)).getFirst();
            Path archive = work.resolve("normalization.zip");
            ProcessBuilder command = sandbox.command(comparisons.datasetPath(run.datasetId()), copy, archive,
                    Mode.NORMALIZE, parameters, null);
            if (!runProcess(run, command, "SANDBOX", "изолированной нормализации")) {
                return false;
            }
            sandbox.extract(archive, output.getParent(), Mode.NORMALIZE);
            return true;
        }

        FrozenFile file = snapshot.normalizer();
        Path input = restore(Files.createDirectory(work.resolve("configured-normalizer")), file);
        Path copy = sources.snapshot(run.id(), StageName.NORMALIZATION,
                work.resolve("normalization-source"), List.of(input)).getFirst();
        Files.createDirectory(output.getParent());
        ProcessBuilder command = new ProcessBuilder(python, "-c", RUN_SNAPSHOT, copy.toString(), file.originalPath(),
                comparisons.datasetPath(run.datasetId()).toString(), output.toString()).redirectErrorStream(true);
        command.environment().put("BALANCE_RUN_PARAMETERS", parameters);
        if (!runProcess(run, command, "NORMALIZATION", "нормализации")) {
            return false;
        }
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            queue.fail(run.id(), "NORMALIZATION_RESULT_INVALID", "Нормализация не вернула файл строк");
            return false;
        }
        return true;
    }

    /** Запускает подготовку фактов на выходе сравнения и сохраняет исходник для журнала. */
    private boolean prepareFacts(Run run, Path comparison, Path membership, Snapshot snapshot, String parameters)
            throws InterruptedException {
        Path work = comparison.getParent();
        try {
            FrozenFile file = snapshot.factsProcedure();
            Path input = file == null
                    ? Files.writeString(work.resolve("facts_inline.py"), snapshot.configuration().factsCode())
                    : restore(Files.createDirectory(work.resolve("configured-facts")), file);
            Path copy = sources.snapshot(run.id(), StageName.FACTS, work.resolve("facts-source"), List.of(input)).getFirst();
            if (file == null) {
                Path archive = work.resolve("facts.zip");
                if (!runProcess(run, sandbox.command(comparison, copy, archive, Mode.FACTS, parameters, null),
                        "FACTS", "изолированной подготовки фактов")) {
                    return false;
                }
                sandbox.extract(archive, membership.getParent(), Mode.FACTS);
            } else {
                Files.createDirectory(membership.getParent());
                ProcessBuilder command = new ProcessBuilder(python, "-c", RUN_SNAPSHOT, copy.toString(),
                        file.originalPath(), comparison.toString(), membership.toString()).redirectErrorStream(true);
                command.environment().put("BALANCE_RUN_PARAMETERS", parameters);
                if (!runProcess(run, command, "FACTS", "подготовки фактов")) {
                    return false;
                }
            }
            if (Files.isRegularFile(membership, LinkOption.NOFOLLOW_LINKS) && Files.size(membership) <= 128L * 1024 * 1024) {
                return true;
            }
        } catch (IOException | RuntimeException error) {
            LOG.warn("Ошибка подготовки фактов запуска {}", run.id(), error);
        }
        queue.fail(run.id(), "FACTS_RESULT_INVALID", "Подготовка фактов не вернула допустимый файл");
        return false;
    }

    /** Проверяет промежуточный файл штатным ограниченным парсером до следующего этапа. */
    private boolean validateNormalized(Run run, Path normalized) throws IOException, InterruptedException {
        ProcessBuilder command = new ProcessBuilder(python, "python-src/compare_dataset.py",
                comparisons.datasetPath(run.datasetId()).toString(), normalized.toString(), "--validate-normalized")
                .redirectErrorStream(true);
        return runProcess(run, command, "NORMALIZATION_RESULT", "проверки нормализованных строк");
    }

    /** Запускает сравнение с отдельными аргументами и ограничивает время работы процесса. */
    private boolean compare(Run run, Path output, FrozenFile procedure, String parameters, Path normalized,
                            AnalysisConfiguration analysis) throws IOException, InterruptedException {
        if (analysis != null && (!analysis.normalization().isEmpty() || !analysis.analysisFields().isEmpty())
                && !script.equals(Path.of("python-src/compare_dataset.py").toAbsolutePath().normalize())) {
            queue.fail(run.id(), "ANALYSIS_UNSUPPORTED", "Табличные настройки требуют штатного скрипта сравнения");
            return false;
        }
        Path original = procedure == null ? script : Path.of(procedure.originalPath());
        Path input = procedure == null ? script
                : restore(Files.createDirectory(output.resolveSibling("configured-procedure")), procedure);
        Path copy = sources.snapshot(run.id(), StageName.COMPARISON,
                output.resolveSibling("comparison-source"), List.of(input)).getFirst();
        ProcessBuilder command = new ProcessBuilder(python, "-c", RUN_SNAPSHOT, copy.toString(), original.toString(),
                comparisons.datasetPath(run.datasetId()).toString(), output.toString()).redirectErrorStream(true);
        command.environment().put("BALANCE_RUN_PARAMETERS", parameters);
        if (normalized != null) {
            command.environment().put("BALANCE_NORMALIZED_PATH", normalized.toString());
        }
        command.environment().remove("BALANCE_NORMALIZATION_RULES");
        if (analysis != null && !analysis.normalization().isEmpty()) {
            Path rules = Files.writeString(output.resolveSibling("normalization-rules.json"),
                    json.writeValueAsString(analysis.normalization()));
            command.environment().put("BALANCE_NORMALIZATION_RULES", rules.toString());
        }
        command.environment().remove("BALANCE_ANALYSIS_FIELDS");
        if (analysis != null && !analysis.analysisFields().isEmpty()) {
            Path fields = Files.writeString(output.resolveSibling("analysis-fields.json"),
                    json.writeValueAsString(analysis.analysisFields()));
            command.environment().put("BALANCE_ANALYSIS_FIELDS", fields.toString());
        }
        return runProcess(run, command, "PYTHON", "обработки набора");
    }

    /** Запускает доверенные файлы проверок и пишет JSON отдельно от диагностики. */
    private boolean check(Run run, Path output) throws IOException, InterruptedException {
        List<Path> files = new ArrayList<>();
        files.add(checkScript);
        try (var entries = Files.list(checksDirectory)) {
            entries.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().matches("check_.*\\.py"))
                    .sorted()
                    .forEach(files::add);
        }
        if (files.size() == 1) {
            queue.fail(run.id(), "CHECKS_EMPTY", "В настроенном каталоге нет проверок");
            return false;
        }
        return launchChecks(run, output, files, files, snapshotChecks, null, "{}", null);
    }

    /** Восстанавливает выбранные проверки из очереди и исполняет их штатным запускателем. */
    private boolean checkFrozen(Run run, Path output, Snapshot snapshot, String parameters) throws IOException, InterruptedException {
        String editorCheck = snapshot.configuration().checkCode();
        List<Check> plan = snapshot.analysis() == null ? null : snapshot.analysis().checks();
        boolean hasRules = plan != null && plan.stream().anyMatch(item -> item.enabled() && item.rule() != null);
        if (snapshot.files().isEmpty() && !hasRules) {
            return isolated(run, output, editorCheck, true, parameters, null);
        }
        Path launcher = Path.of("python-src/run_check.py").toAbsolutePath().normalize();
        Path prepared = Files.createDirectory(output.resolveSibling("configured-checks"));
        List<Path> files = new ArrayList<>(List.of(launcher));
        List<Path> originals = new ArrayList<>(List.of(launcher));
        for (var file : snapshot.files()) {
            files.add(restore(prepared, file));
            originals.add(Path.of(file.originalPath()));
        }
        if (editorCheck != null) {
            Path editor = Files.writeString(prepared.resolve("inline.py"), editorCheck);
            return launchChecks(run, output, files, originals, true, editor, parameters, plan);
        }
        return launchChecks(run, output, files, originals, true, null, parameters, plan);
    }

    /** Восстанавливает сохранённый файл во временном каталоге запуска. */
    private Path restore(Path directory, FrozenFile file) throws IOException {
        return Files.write(directory.resolve(file.name()), Base64.getDecoder().decode(file.base64()));
    }

    /** Сохраняет исполняемый исходник и запускает проверки с выбранным контекстом путей. */
    private boolean launchChecks(Run run, Path output, List<Path> files, List<Path> originals, boolean freeze, Path editor, String parameters, List<Check> plan)
            throws IOException, InterruptedException {
        List<Path> sourceFiles = new ArrayList<>(freeze ? files : files.subList(0, 1));
        if (editor != null) {
            sourceFiles.add(editor);
        }
        List<Path> copies = sources.snapshot(run.id(), StageName.CHECKS, output.resolveSibling("check-source"), sourceFiles);
        List<String> args = new ArrayList<>(List.of(python, "-c", RUN_SNAPSHOT, copies.getFirst().toString(),
                files.getFirst().toString(), output.toString(), "--report"));
        (freeze ? copies.subList(0, files.size()) : files).stream().skip(1).map(Path::toString).forEach(args::add);
        ProcessBuilder command = new ProcessBuilder(args).redirectOutput(output.resolve("checks.json").toFile());
        if (freeze) {
            command.environment().put("BALANCE_CHECK_SNAPSHOT_MAP", sources.checkLocations(originals, copies));
        }
        command.environment().put("BALANCE_RUN_PARAMETERS", parameters);
        command.environment().remove("BALANCE_CONFIGURED_CHECKS");
        if (plan != null) {
            Path planFile = Files.writeString(output.resolveSibling("checks-plan.json"), json.writeValueAsString(plan));
            command.environment().put("BALANCE_CONFIGURED_CHECKS", planFile.toString());
        }
        if (!runProcess(run, command, "CHECKS", "выполнения проверок")) {
            return false;
        }
        return editor == null || executeIsolated(run, output, copies.getLast(), true, parameters, null);
    }

    /** Сохраняет код редактора и запускает его только внутри изоляции. */
    private boolean isolated(Run run, Path output, String code, boolean checks, String parameters, Path normalized) throws IOException, InterruptedException {
        Path input = Files.writeString(output.resolveSibling("inline.py"), code);
        Path copy = sources.snapshot(run.id(), checks ? StageName.CHECKS : StageName.COMPARISON,
                output.resolveSibling(checks ? "inline-check-source" : "inline-comparison-source"), List.of(input)).getFirst();
        return executeIsolated(run, output, copy, checks, parameters, normalized);
    }

    /** Получает ограниченный архив результата, сохраняя ошибку на текущем этапе. */
    private boolean executeIsolated(Run run, Path output, Path script, boolean checks, String parameters, Path normalized) throws InterruptedException {
        Path archive = output.resolveSibling(checks ? "inline-check.zip" : "inline-comparison.zip");
        Path input = checks ? output : comparisons.datasetPath(run.datasetId());
        try {
            if (!runProcess(run, sandbox.command(input, script, archive, checks ? Mode.CHECK : Mode.COMPARE, parameters, normalized), "SANDBOX",
                    "изолированного Python")) {
                return false;
            }
            Path isolatedOutput = checks ? output.resolveSibling("inline-check-result") : output;
            sandbox.extract(archive, isolatedOutput, checks ? Mode.CHECK : Mode.COMPARE);
            if (checks) {
                mergeChecks(output.resolve("checks.json"), isolatedOutput.resolve("checks.json"));
            }
            return true;
        } catch (IOException | RuntimeException error) {
            LOG.warn("Некорректный изолированный результат запуска {}", run.id(), error);
            queue.fail(run.id(), "SANDBOX_RESULT_INVALID", "Изолированный Python не вернул допустимый результат");
            return false;
        }
    }

    /** Объединяет результаты, сохраняя уникальность идентификаторов проверок. */
    private void mergeChecks(Path target, Path inline) throws IOException {
        if (Files.size(inline) > 4L * 1024 * 1024) {
            throw new IOException("Слишком большой отчёт проверки");
        }
        JsonNode added = json.readTree(Files.readString(inline));
        if (!added.path("checks").isArray() || added.path("checks").size() != 1 || !added.path("rows").isArray()) {
            throw new IOException("Неожиданный формат отчёта проверки");
        }
        var report = json.createObjectNode();
        var checks = report.putArray("checks");
        var rows = report.putArray("rows");
        Set<String> ids = new HashSet<>();
        if (Files.exists(target)) {
            JsonNode existing = json.readTree(Files.readString(target));
            for (JsonNode check : existing.path("checks")) {
                ids.add(check.path("id").asString());
                checks.add(check);
            }
            for (JsonNode row : existing.path("rows")) {
                rows.add(row);
            }
        }
        JsonNode check = added.path("checks").get(0);
        if (!check.path("id").isString() || check.path("id").asString().isBlank()
                || !ids.add(check.path("id").asString()) || !check.path("label").isString()
                || !check.path("row_count").isIntegralNumber()
                || check.path("row_count").asInt() != added.path("rows").size()) {
            throw new IOException("Некорректная или повторяющаяся проверка");
        }
        checks.add(check);
        for (JsonNode row : added.path("rows")) {
            if (!validCheckRow(row)) {
                throw new IOException("Некорректная строка изолированной проверки");
            }
            rows.add(row);
        }
        Files.writeString(target, json.writeValueAsString(report));
    }

    /** Проверяет контракт ответа за пределами процесса пользовательского кода. */
    private boolean validCheckRow(JsonNode row) {
        if (!row.isObject() || !row.path("LABEL").isString() || !row.path("ARGUMENT").isString()
                || !Set.of("BEFORE", "AFTER").contains(row.path("SOURCE").asString(""))
                || !Set.of("PASS", "FAIL").contains(row.path("RESULT").asString(""))
                || !Set.of("HIGH", "LOW").contains(row.path("CONFIDENCE").asString(""))
                || !row.path("METRIC").isNumber() || !Double.isFinite(row.path("METRIC").asDouble())) {
            return false;
        }
        for (String field : List.of("CNT", "TOTAL")) {
            JsonNode value = row.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
                return false;
            }
        }
        JsonNode apps = row.path("APP_IDS");
        if (!apps.isArray()) {
            return false;
        }
        for (JsonNode id : apps) {
            if (!id.isString() || id.asString().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Ограничивает время дочернего процесса и считывает диагностику параллельно. */
    private boolean runProcess(Run run, ProcessBuilder command, String code, String stage)
            throws IOException, InterruptedException {
        command.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process process = command.start();
        FutureTask<String> diagnostic = new FutureTask<>(() -> readDiagnostic(command.redirectErrorStream()
                ? process.getInputStream() : process.getErrorStream()));
        Thread.ofVirtual().start(diagnostic);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                queue.fail(run.id(), code + "_TIMEOUT", "Истекло время " + stage);
                return false;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                LOG.warn("Python завершился с кодом {} для запуска {}: {}", exit, run.id(),
                        "SANDBOX".equals(code) ? "вывод пользовательского скрипта скрыт" : diagnostic(diagnostic));
                queue.fail(run.id(), code + "_FAILED", "Ошибка " + stage);
                return false;
            }
            return true;
        } finally {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } finally {
                diagnostic.cancel(true);
            }
        }
    }

    /** Получает диагностику без бесконечного ожидания закрытия канала. */
    private String diagnostic(FutureTask<String> result) throws InterruptedException {
        try {
            return result.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException error) {
            return "вывод процесса недоступен";
        }
    }

    /** Освобождает канал процесса, сохраняя в памяти только начало диагностики. */
    private String readDiagnostic(InputStream stream) throws IOException {
        try (stream) {
            byte[] prefix = stream.readNBytes(DIAGNOSTIC_LIMIT);
            byte[] buffer = new byte[8192];
            while (stream.read(buffer) != -1) {
                // Остальной вывод нужен только для освобождения буфера процесса.
            }
            return new String(prefix, StandardCharsets.UTF_8);
        }
    }

    /** Удаляет временные данные после завершения или ошибки обработки. */
    private void cleanup(Path work) {
        if (work == null) {
            return;
        }
        try {
            FileSystemUtils.deleteRecursively(work);
        } catch (IOException error) {
            LOG.warn("Не удалось удалить временные данные сравнения", error);
        }
    }
}

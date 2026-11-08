package com.kratti.service;

import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.AnalysisConfiguration.AnalysisField;
import com.kratti.dto.response.AnalysisConfiguration.NormalizationRule;
import com.kratti.dto.response.DatasetComparisonViews.Application;
import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import com.kratti.dto.response.DatasetComparisonViews.DatasetPreview;
import com.kratti.dto.response.DatasetComparisonViews.Decision;
import com.kratti.dto.response.DatasetComparisonViews.Detail;
import com.kratti.dto.response.DatasetComparisonViews.Page;
import com.kratti.dto.response.DatasetComparisonViews.PageMetadata;
import com.kratti.dto.response.DatasetComparisonViews.Report;
import com.kratti.dto.response.DatasetComparisonViews.Run;
import com.kratti.dto.response.DatasetConfigurationViews.Selection;
import com.kratti.dto.response.DatasetConfigurationViews.Snapshot;
import com.kratti.enums.RunStatus;
import com.kratti.exception.ApiException;
import com.kratti.mapper.AnalysisConfigurationMapper;
import com.kratti.repository.DatasetComparisonRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Регистрирует серверный набор и управляет доступом к результатам сравнения. */
@Service
public class DatasetComparisonService {
    private static final int MAX_PREVIEW_JSON_BYTES = 256 * 1024;
    private final DatasetComparisonRepository repository;
    private final ObjectMapper json;
    private final DatasetConfigurationService configurations;
    private final String python;
    private final AnalysisConfigurationMapper analysisConfigurations;
    private final List<DatasetSourceProvider> sourceProviders;
    private volatile Map<UUID, DatasetSource> catalog = Map.of();

    /** Подключает зарегистрированные адаптеры и сервисы исполнения. */
    public DatasetComparisonService(DatasetComparisonRepository repository, ObjectMapper json,
                                    DatasetConfigurationService configurations, AnalysisConfigurationMapper analysisConfigurations,
                                    @Value("${balance.python.executable:python3}") String python,
                                    List<DatasetSourceProvider> sourceProviders) {
        this.repository = repository;
        this.json = json;
        this.configurations = configurations;
        this.analysisConfigurations = analysisConfigurations;
        this.python = python;
        this.sourceProviders = List.copyOf(sourceProviders);
    }

    /** Регистрирует источники и проверяет уникальность идентификаторов. */
    public void registerDataset() throws IOException {
        Map<UUID, DatasetSource> registered = new LinkedHashMap<>();
        for (DatasetSourceProvider provider : sourceProviders) {
            for (DatasetSource source : provider.discover()) {
                Dataset dataset = source.dataset();
                if (registered.putIfAbsent(dataset.id(), source) != null) {
                    throw new IllegalArgumentException("Повторяющийся ID манифеста: " + dataset.sourceRunId());
                }
            }
        }
        registered.values().forEach(item -> repository.register(item.dataset()));
        catalog = Map.copyOf(registered);
    }

    /** Возвращает решения по первой паре из зарегистрированного пакета. */
    public DatasetPreview preview(UUID id) {
        DatasetSource source = requireRegistered(id);
        try {
            var sample = source.sample();
            return new DatasetPreview(sample.applicationId(), sample.beforeLabel(), sample.afterLabel(),
                    previewDecision(sample.before()), previewDecision(sample.after()));
        } catch (IOException | RuntimeException error) {
            throw new ApiException(422, "DATASET_PREVIEW_UNAVAILABLE", "Не удалось прочитать пример пары ответов");
        }
    }

    /** Применяет правила черновика к той же паре без сохранения конфигурации. */
    public JsonNode previewNormalization(UUID id, List<NormalizationRule> rules) {
        return previewTables(id, rules, List.of());
    }

    /** Применяет правила и интервалы из черновика к одной паре. */
    public JsonNode previewTables(UUID id, List<NormalizationRule> rules, List<AnalysisField> fields) {
        analysisConfigurations.validateNormalization(rules, null, null, null, null);
        analysisConfigurations.validateAnalysisFields(fields, null, null);
        DatasetSource source = requireRegistered(id);
        try {
            var sample = source.sample();
            return runNormalizationPreview(Map.of("rules", rules, "fields", fields,
                    "before", sample.before(), "after", sample.after()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "DATASET_PREVIEW_UNAVAILABLE", "Предпросмотр прерван");
        } catch (IOException | RuntimeException error) {
            throw new ApiException(422, "DATASET_PREVIEW_UNAVAILABLE", "Не удалось подготовить пример пары");
        }
    }

    /** Ограничивает время Python и удаляет временные файлы с исходной парой. */
    private JsonNode runNormalizationPreview(Map<String, Object> payload) throws IOException, InterruptedException {
        Path input = Files.createTempFile("balance-preview-", ".json");
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("balance-preview-", ".result");
            Files.writeString(input, json.writeValueAsString(payload));
            process = new ProcessBuilder(python, "-B", "python-src/preview_normalization.py")
                    .redirectInput(input.toFile()).redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Не удалось выполнить предпросмотр");
            }
            return readPreviewJson(output);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(input);
            if (output != null) {
                Files.deleteIfExists(output);
            }
        }
    }

    /** Читает только решение заявки и проверяет его принадлежность контракту. */
    private String previewDecision(String source) throws IOException {
        String decision = parseSourceJson(source).path("decisionResult").path("decision").asString("");
        if (!List.of("APPROVE", "REJECT").contains(decision)) {
            throw new IOException("Решение заявки отсутствует или некорректно");
        }
        return decision;
    }

    /** Ограничивает размер читаемого JSON и исключает символьную ссылку вместо файла. */
    private JsonNode readPreviewJson(Path file) throws IOException {
        return parseSourceJson(readPreviewText(file));
    }

    /** Читает ограниченный UTF-8 без замены повреждённых символов. */
    private String readPreviewText(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_PREVIEW_JSON_BYTES + 1);
            if (bytes.length > MAX_PREVIEW_JSON_BYTES) {
                throw new IOException("Превышен допустимый размер JSON");
            }
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        }
    }

    /** Разбирает JSON, пропуская начальную метку UTF-8, если она присутствует. */
    private JsonNode parseSourceJson(String value) {
        return json.readTree(value.startsWith("\uFEFF") ? value.substring(1) : value);
    }

    /** Получает отпечаток входа от зарегистрированного адаптера. */
    public String inputFingerprint(UUID id) throws IOException {
        return requireRegistered(id).fingerprint();
    }

    /** Отмечает версию штатных Python-модулей и настроенной процедуры по их содержимому. */
    public String runtimeFingerprint(Path procedure) throws IOException {
        MessageDigest digest = sha256();
        try (var files = Files.list(Path.of("python-src"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".py")).sorted().toList()) {
                digest.update(sha256().digest(file.getFileName().toString().getBytes(StandardCharsets.UTF_8)));
                digest.update(fileFingerprint(file.toRealPath()));
            }
        }
        if (Files.isRegularFile(procedure)) {
            digest.update(fileFingerprint(procedure.toRealPath()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Повторяет завершённый запуск из сохранённого снимка. */
    public Run replay(UUID id, UUID workspace) {
        Run original = status(id);
        requireDataset(original.datasetId());
        return repository.enqueueReplay(id, workspace).orElseThrow(() ->
                new ApiException(409, "REPLAY_UNAVAILABLE", "Для повтора нужен завершённый запуск с зафиксированными настройками и входом"));
    }

    /** Читает обычный файл порциями и возвращает двоичный SHA-256. */
    private byte[] fileFingerprint(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16384];
            for (int count; (count = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    /** Создаёт обязательный для Java алгоритм SHA-256. */
    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Возвращает путь именно к набору запущенной конфигурации. */
    public Path datasetPath(UUID id) {
        return requireRegistered(id).executionPackage();
    }

    /** Проверяет, что выбранный набор доступен в текущей конфигурации. */
    public Dataset requireDataset(UUID id) {
        return requireRegistered(id).dataset();
    }

    /** Отклоняет неизвестный или временно отключённый набор. */
    private DatasetSource requireRegistered(UUID id) {
        DatasetSource item = id == null ? null : catalog.get(id);
        if (item == null) {
            throw new ApiException(404, "DATASET_NOT_FOUND", "Набор заявок не найден");
        }
        return item;
    }

    /** Возвращает упорядоченную страницу активных наборов. */
    public Page<Dataset> datasets(int page, int size) {
        long offset = Pagination.request(page, size).getOffset();
        Map<UUID, DatasetSource> active = catalog;
        List<Dataset> items = active.values().stream().map(DatasetSource::dataset)
                .sorted(Comparator.comparing(Dataset::sourceRunId)).skip(offset).limit(size).toList();
        return page(items, page, size, active.size());
    }

    /** Возвращает параметры, сохранённые при постановке запуска в очередь. */
    public JsonNode frozenParameters(UUID id) {
        status(id);
        return json.readTree(repository.frozenParameters(id));
    }

    /** Ставит в очередь сравнение зарегистрированного набора. */
    public Run create(UUID datasetId, UUID workspace, UUID configurationId) {
        requireDataset(datasetId);
        return repository.enqueue(datasetId, workspace, configurationId == null
                ? null : configurations.freeze(workspace, configurationId, datasetId));
    }

    /** Фиксирует черновик только внутри транзакции постановки и не оставляет его в каталоге конфигураций. */
    @Transactional
    public Run createDraft(UUID datasetId, UUID workspace, AnalysisConfiguration draft) {
        requireDataset(datasetId);
        if (draft == null) {
            throw new ApiException(400, "BAD_REQUEST", "Передайте настройки черновика");
        }
        String name = draft.name() == null || draft.name().isBlank() ? "Разовый запуск" : draft.name();
        AnalysisConfiguration namedDraft = new AnalysisConfiguration(draft.schemaVersion(), null, name,
                draft.source(), draft.normalization(), draft.analysisFields(), draft.stages(), draft.checks(),
                draft.legacyParameters());
        Selection selection = analysisConfigurations.toLegacy(namedDraft);
        AnalysisConfiguration saved = configurations.saveV2(workspace, null, selection, namedDraft);
        Snapshot snapshot = configurations.freeze(workspace, saved.id(), datasetId);
        Run run = repository.enqueue(datasetId, workspace, snapshot);
        configurations.deleteDraft(workspace, saved.id());
        return run;
    }

    /** Возвращает страницу истории из согласованного снимка очереди. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Run> history(UUID workspace, int page, int size) {
        long offset = Pagination.request(page, size).getOffset();
        return page(repository.history(workspace, size, offset), page, size, repository.countRuns(workspace));
    }

    /** Возвращает статус или ошибку отсутствующего запуска. */
    public Run status(UUID id) {
        return repository.find(id).orElseThrow(
                () -> new ApiException(404, "COMPARISON_NOT_FOUND", "Запуск сравнения не найден"));
    }

    /** Возвращает сводку после завершения импорта. */
    public Report report(UUID id) {
        requireCompleted(id);
        return repository.report(id);
    }

    /** Читает отчёт проверок завершённого запуска или сообщает об его отсутствии. */
    public JsonNode checkReport(UUID id) {
        requireCompleted(id);
        String report = repository.checkReport(id);
        JsonNode skipped = json.readTree(repository.skippedChecks(id));
        if (report == null && skipped.isEmpty()) {
            throw new ApiException(404, "CHECK_REPORT_NOT_FOUND", "В этом запуске проверки не выполнялись");
        }
        var result = json.createObjectNode();
        if (report == null) {
            result.putArray("checks");
            result.putArray("rows");
        } else {
            JsonNode saved = json.readTree(report);
            result.set("checks", saved.path("checks"));
            result.set("rows", saved.path("rows"));
        }
        result.set("skipped", skipped);
        return result;
    }

    /** Возвращает страницу заявок после совместного применения фильтров. */
    public Page<Application> applications(UUID id, int page, int size, Boolean diff, Boolean changed,
                                         Decision before, Decision after) {
        long offset = Pagination.request(page, size).getOffset();
        requireCompleted(id);
        return page(repository.applications(id, diff, changed, before, after, size, offset),
                page, size, repository.countApplications(id, diff, changed, before, after));
    }

    /** Возвращает решения и отличия заявки из опубликованного отчёта. */
    public Detail detail(UUID id, String applicationId) {
        requireCompleted(id);
        Application application = repository.application(id, applicationId).orElseThrow(
                () -> new ApiException(404, "APPLICATION_NOT_FOUND", "Заявка в запуске не найдена"));
        return new Detail(application.applicationId(), application.decisionBefore(), application.decisionAfter(),
                application.hasDiff(), application.decisionChanged(), repository.differences(id, applicationId));
    }

    /** Различает ожидание и ошибку запуска по контракту отчёта. */
    private void requireCompleted(UUID id) {
        Run run = status(id);
        if (run.status() == RunStatus.FAILED) {
            throw new ApiException(409, "COMPARISON_FAILED", run.error().message());
        }
        if (run.status() != RunStatus.COMPLETED) {
            throw new ApiException(409, "COMPARISON_NOT_FINISHED", "Сравнение ещё выполняется");
        }
    }

    /** Формирует метаданные страницы с учётом полного количества строк. */
    private <T> Page<T> page(List<T> items, int page, int size, long count) {
        return new Page<>(items, new PageMetadata(page, size, count, Math.ceilDiv(count, size)));
    }
}

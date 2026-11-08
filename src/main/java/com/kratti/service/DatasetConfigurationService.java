package com.kratti.service;

import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.AnalysisConfiguration.AnalysisField;
import com.kratti.dto.response.AnalysisConfiguration.Check;
import com.kratti.dto.response.AnalysisConfiguration.NormalizationRule;
import com.kratti.dto.response.DatasetConfigurationViews.Configuration;
import com.kratti.dto.response.DatasetConfigurationViews.FrozenFile;
import com.kratti.dto.response.DatasetConfigurationViews.Selection;
import com.kratti.dto.response.DatasetConfigurationViews.Snapshot;
import com.kratti.dto.response.Violation;
import com.kratti.exception.ApiException;
import com.kratti.mapper.AnalysisConfigurationMapper;
import com.kratti.repository.DatasetConfigurationRepository;
import com.kratti.repository.DatasetConfigurationRepository.Stored;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Сохраняет пользовательский выбор и готовит его неизменяемую копию для очереди. */
@Service
public class DatasetConfigurationService {
    private static final Pattern PYTHON_ENCODING = Pattern.compile("^[ \t\f]*#.*?coding[:=][ \t]*([-_.a-zA-Z0-9]+)");
    private static final int MAX_SCRIPT_BYTES = 256 * 1024;
    private final DatasetConfigurationRepository repository;
    private final Path directory;
    private final Path procedures;
    private final boolean bundledProcedures;
    private final DatasetPythonSandbox sandbox;
    private final ObjectMapper json;
    private final String python;
    private final AnalysisConfigurationMapper analysisConfigurations;

    /** Настраивает тот же доверенный каталог, который использует штатный запуск. */
    public DatasetConfigurationService(DatasetConfigurationRepository repository,
                                       @Value("${balance.checks.path:}") String checksPath,
                                       @Value("${balance.procedures.path:}") String proceduresPath,
                                       DatasetPythonSandbox sandbox, AnalysisConfigurationMapper analysisConfigurations,
                                       ObjectMapper json, @Value("${balance.python.executable:python3}") String python) {
        this.repository = repository;
        this.sandbox = sandbox;
        this.json = json;
        this.python = python;
        this.analysisConfigurations = analysisConfigurations;
        this.directory = checksPath.isBlank() ? null : Path.of(checksPath).toAbsolutePath().normalize();
        this.bundledProcedures = proceduresPath.isBlank();
        this.procedures = Path.of(bundledProcedures ? "python-src" : proceduresPath).toAbsolutePath().normalize();
    }

    /** Возвращает доступные имена файлов без публикации серверных путей. */
    public List<String> availableChecks() {
        return catalog(directory, "check_.*\\.py");
    }

    /** Возвращает штатные процедуры или файлы из доверенного каталога оператора. */
    public List<String> availableProcedures() {
        return catalog(procedures, bundledProcedures
                ? "(compare_dataset|normalize_dataset|prepare_changed_facts)\\.py"
                : "[A-Za-z0-9][A-Za-z0-9_.-]*\\.py");
    }

    /** Читает только файл из доверенного каталога и возвращает редактируемую копию. */
    public JsonNode copyScript(String kind, String name) {
        List<String> available = switch (kind) {
            case "check" -> availableChecks();
            case "procedure" -> availableProcedures();
            default -> throw invalid("kind", "Укажите каталог скрипта");
        };
        if (!available.contains(name)) {
            throw new ApiException(404, "SCRIPT_NOT_FOUND", "Файл скрипта не найден");
        }
        Path file = ("check".equals(kind) ? directory : procedures).resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(404, "SCRIPT_NOT_FOUND", "Файл скрипта не найден");
        }
        try (var source = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] contents = source.readNBytes(MAX_SCRIPT_BYTES + 1);
            if (contents.length > MAX_SCRIPT_BYTES) {
                throw invalid("name", "Файл скрипта слишком велик");
            }
            JsonNode result = inspectScript(contents, "copy");
            String code = editorCode(result.path("code").asString(""), "code");
            if (code == null) {
                throw invalid("code", "Нет доступного текста для копирования");
            }
            return result;
        } catch (IOException error) {
            throw new ApiException(404, "SCRIPT_NOT_FOUND", "Не удалось прочитать файл скрипта");
        }
    }

    /** Проверяет синтаксис черновика без запуска кода и доступа к данным набора. */
    public JsonNode validateScript(String code) {
        String value = editorCode(code, "code");
        if (value == null) {
            throw invalid("code", "Введите код скрипта");
        }
        return inspectScript(value.getBytes(StandardCharsets.UTF_8), "validate");
    }

    /** Вызывает ограниченный по времени статический инспектор Python. */
    private JsonNode inspectScript(byte[] input, String mode) {
        Path source = null;
        Path result = null;
        Process process = null;
        try {
            source = Files.createTempFile("balance-script-", ".source");
            result = Files.createTempFile("balance-script-", ".json");
            Files.write(source, input);
            process = new ProcessBuilder(python, "-I", "-B", "python-src/inspect_script.py", mode)
                    .redirectInput(source.toFile()).redirectOutput(result.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Инспектор скрипта не завершился");
            }
            return json.readTree(Files.readString(result));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "SCRIPT_INSPECTION_UNAVAILABLE", "Проверка кода прервана");
        } catch (IOException error) {
            throw new ApiException(503, "SCRIPT_INSPECTION_UNAVAILABLE", "Не удалось проверить код");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteInspectionFile(source);
            deleteInspectionFile(result);
        }
    }

    /** Удаляет промежуточный файл статического анализа. */
    private void deleteInspectionFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException error) {
                path.toFile().deleteOnExit();
            }
        }
    }

    /** Читает имена обычных файлов из настроенного оператором каталога. */
    private List<String> catalog(Path folder, String pattern) {
        if (folder == null) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> name.matches(pattern)).sorted().toList();
        } catch (IOException error) {
            throw new ApiException(503, "SCRIPT_CATALOG_UNAVAILABLE", "Каталог Python-скриптов недоступен");
        }
    }

    /** Возвращает сохранённые конфигурации владельца. */
    public List<Configuration> list(UUID workspace) {
        return repository.list(workspace);
    }

    /** Возвращает документы для общей формы простого и экспертного режимов. */
    public List<AnalysisConfiguration> listV2(UUID workspace) {
        return repository.listStored(workspace).stream().map(this::analysis).toList();
    }

    /** Открывает сохранённые настройки только в рабочей области владельца. */
    public Configuration get(UUID workspace, UUID id) {
        return stored(workspace, id).legacy();
    }

    /** Возвращает сохранённый документ или строит его из записи до миграции. */
    public AnalysisConfiguration getV2(UUID workspace, UUID id) {
        return analysis(stored(workspace, id));
    }

    /** Читает согласованные настройки и документ по одному запросу. */
    private Stored stored(UUID workspace, UUID id) {
        return repository.find(workspace, id).orElseThrow(this::notFound);
    }

    /** Приводит настройки до миграции к текущей версии без обновления базы. */
    private AnalysisConfiguration analysis(Stored stored) {
        AnalysisConfiguration value = stored.analysis() == null
                ? analysisConfigurations.fromLegacy(stored.legacy()) : stored.analysis();
        return analysisConfigurations.ordered(value, false);
    }

    /** Создаёт либо изменяет конфигурацию после проверки выбранных имён. */
    @Transactional
    public Configuration save(UUID workspace, UUID id, Selection selection) {
        return save(workspace, id, selection, null).legacy();
    }

    /** Сохраняет выбранный порядок v2 и возвращает документ этой операции записи. */
    public AnalysisConfiguration saveV2(UUID workspace, UUID id, Selection selection, AnalysisConfiguration requested) {
        return save(workspace, id, selection, requested).analysis();
    }

    /** Сохраняет согласованные представления и оставляет порядок при правке из простой формы. */
    private Stored save(UUID workspace, UUID id, Selection selection, AnalysisConfiguration requested) {
        if (selection == null || selection.name() == null || selection.name().isBlank() || selection.name().indexOf('\0') >= 0
                || selection.name().strip().length() > 100) {
            throw invalid("name", "Укажите имя до 100 символов");
        }
        if (selection.datasetId() == null) {
            throw invalid("datasetId", "Выберите набор");
        }
        if (selection.checks() == null || selection.checks().size() > 40
                || new HashSet<>(selection.checks()).size() != selection.checks().size()) {
            throw invalid("checks", "Укажите до 40 проверок без повторов");
        }
        if (!selection.checks().isEmpty() && !availableChecks().containsAll(selection.checks())) {
            throw invalid("checks", "Выберите проверки из доступного каталога");
        }
        String procedureCode = editorCode(selection.procedureCode(), "procedureCode");
        String checkCode = editorCode(selection.checkCode(), "checkCode");
        if (selection.procedure() != null && procedureCode != null) {
            throw invalid("procedure", "Выберите файл процедуры или код из редактора");
        }
        if (selection.procedure() != null && !availableProcedures().contains(selection.procedure())) {
            throw invalid("procedure", "Выберите процедуру из доступного каталога");
        }
        String normalizerCode = editorCode(selection.normalizerCode(), "normalizerCode");
        if (selection.normalizer() != null && (normalizerCode != null
                || !availableProcedures().contains(selection.normalizer()))) {
            throw invalid("normalizer", "Выберите файл нормализации или код из редактора");
        }
        String factsCode = editorCode(selection.factsCode(), "factsCode");
        if (selection.factsProcedure() != null && (factsCode != null
                || !availableProcedures().contains(selection.factsProcedure()))) {
            throw invalid("factsProcedure", "Выберите доступную процедуру подготовки фактов или код редактора");
        }
        var value = new Configuration(id == null ? UUID.randomUUID() : id, selection.name().strip(),
                selection.datasetId(), List.copyOf(selection.checks()), selection.procedure(), procedureCode, checkCode, parameters(selection.parameters()), selection.normalizer(), normalizerCode, selection.factsProcedure(), factsCode);
        AnalysisConfiguration previous = requested == null && id != null
                ? analysis(repository.lock(workspace, id).orElseThrow(this::notFound)) : null;
        boolean orderedFactsFirst = false;
        List<NormalizationRule> rules = requested != null ? requested.normalization()
                : previous == null ? List.of() : previous.normalization();
        analysisConfigurations.validateNormalization(rules, selection.normalizer(), normalizerCode,
                selection.procedure(), procedureCode);
        List<AnalysisField> fields = requested != null ? requested.analysisFields()
                : previous == null ? List.of() : previous.analysisFields();
        analysisConfigurations.validateAnalysisFields(fields, selection.procedure(), procedureCode);
        AnalysisConfiguration document = analysisConfigurations.withTables(
                analysisConfigurations.ordered(analysisConfigurations.fromLegacy(value), orderedFactsFirst), rules, fields);
        if (requested != null) {
            document = analysisConfigurations.withChecks(document, requested.checks());
        } else if (previous != null && previous.checks().stream().anyMatch(check -> !check.enabled() || check.rule() != null)) {
            var previousScripts = previous.checks().stream().filter(Check::enabled).filter(check -> check.rule() == null).toList();
            if (!previousScripts.equals(document.checks())) {
                throw invalid("checks", "Состав расширенного плана проверок меняется в конфигураторе v2");
            }
            document = analysisConfigurations.withChecks(document, previous.checks());
        }
        Configuration saved = id == null ? repository.insert(workspace, value, document)
                : repository.update(workspace, value, document).orElseThrow(this::notFound);
        return new Stored(saved, document);
    }

    /** Фиксирует актуальный выбор и содержимое файлов до постановки в очередь. */
    public Snapshot freeze(UUID workspace, UUID id, UUID dataset) {
        Stored saved = stored(workspace, id);
        Configuration config = saved.legacy();
        if (!config.datasetId().equals(dataset)) {
            throw new ApiException(400, "CONFIGURATION_DATASET_MISMATCH", "Конфигурация относится к другому набору");
        }
        if ((config.procedureCode() != null || config.checkCode() != null || config.normalizerCode() != null || config.factsCode() != null) && !sandbox.enabled()) {
            throw new ApiException(503, "PYTHON_SANDBOX_DISABLED", "В этой среде изолированный запуск Python отключён");
        }
        List<FrozenFile> files = new ArrayList<>();
        if (!config.checks().isEmpty() && !availableChecks().containsAll(config.checks())) {
            throw new ApiException(409, "CONFIGURATION_CHECK_MISSING", "Одна из сохранённых проверок недоступна");
        }
        for (String name : config.checks()) {
            files.add(freezeFile(directory.resolve(name)));
        }
        FrozenFile procedure = null;
        if (config.procedure() != null) {
            if (!availableProcedures().contains(config.procedure())) {
                throw new ApiException(409, "CONFIGURATION_PROCEDURE_MISSING", "Сохранённая процедура недоступна");
            }
            procedure = freezeFile(procedures.resolve(config.procedure()));
        }
        FrozenFile normalizer = null;
        if (config.normalizer() != null) {
            if (!availableProcedures().contains(config.normalizer())) {
                throw new ApiException(409, "CONFIGURATION_PROCEDURE_MISSING", "Сохранённая нормализация недоступна");
            }
            normalizer = freezeFile(procedures.resolve(config.normalizer()));
        }
        FrozenFile factsProcedure = null;
        if (config.factsProcedure() != null) {
            if (!availableProcedures().contains(config.factsProcedure())) {
                throw new ApiException(409, "CONFIGURATION_PROCEDURE_MISSING", "Файл подготовки фактов недоступен");
            }
            factsProcedure = freezeFile(procedures.resolve(config.factsProcedure()));
        }
        return new Snapshot(config, files, procedure, normalizer, factsProcedure, analysis(saved));
    }

    /** Удаляет временную конфигурацию после фиксации её снимка в очереди. */
    public void deleteDraft(UUID workspace, UUID id) {
        repository.delete(workspace, id);
    }

    /** Принимает ограниченный JSON с объектом процедуры и объектами параметров проверок. */
    private JsonNode parameters(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject() || value.toString().getBytes(StandardCharsets.UTF_8).length > 32768
                || hasNul(value)) {
            throw invalid("parameters", "Параметры должны быть JSON-объектом до 32 КиБ");
        }
        for (var entry : value.properties()) {
            if (!List.of("procedure", "checks").contains(entry.getKey()) || !entry.getValue().isObject()) {
                throw invalid("parameters", "Параметры допускают объекты procedure и checks");
            }
            if ("checks".equals(entry.getKey())) {
                for (var check : entry.getValue().properties()) {
                    if (check.getKey().isBlank() || !check.getValue().isObject()) {
                        throw invalid("parameters", "Укажите объект параметров для каждой проверки");
                    }
                }
            }
        }
        return value;
    }

    /** Отклоняет нулевые символы в ключах и строках, которые PostgreSQL не хранит в JSONB. */
    private boolean hasNul(JsonNode value) {
        if (value.isString()) {
            return value.asString().indexOf(0) >= 0;
        }
        if (value.isObject()) {
            return value.properties().stream().anyMatch(entry -> entry.getKey().indexOf(0) >= 0 || hasNul(entry.getValue()));
        }
        if (value.isArray()) {
            for (JsonNode entry : value) {
                if (hasNul(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Нормализует отсутствие текста и ограничивает размер пользовательского скрипта. */
    private String editorCode(String code, String field) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (code.indexOf('\0') >= 0 || code.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES) {
            throw invalid(field, "Недопустимый или слишком большой код");
        }
        if (!usesEditorEncoding(code)) {
            throw invalid(field, "В редакторе допускается только UTF-8");
        }
        return code;
    }

    /** Проверяет действующую декларацию Python в первых двух строках. */
    private boolean usesEditorEncoding(String code) {
        String text = code.startsWith("\uFEFF") ? code.substring(1) : code;
        for (String line : text.lines().limit(2).toList()) {
            var marker = PYTHON_ENCODING.matcher(line);
            if (marker.find()) {
                String encoding = marker.group(1).toLowerCase(Locale.ROOT).replace('_', '-');
                return encoding.startsWith("utf-8-") || List.of("utf-8", "utf8", "u8", "utf",
                        "utf8-ucs2", "utf8-ucs4", "cp65001").contains(encoding);
            }
            if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                break;
            }
        }
        return true;
    }

    /** Читает байты файла с ограничением размера и сохраняет исходный путь для исполнения. */
    private FrozenFile freezeFile(Path path) {
        try (var file = Files.newInputStream(path)) {
            byte[] bytes = file.readNBytes(MAX_SCRIPT_BYTES + 1);
            if (bytes.length > MAX_SCRIPT_BYTES) {
                throw new ApiException(409, "CONFIGURATION_SCRIPT_TOO_LARGE", "Python-файл слишком велик");
            }
            return new FrozenFile(path.getFileName().toString(), path.toString(), Base64.getEncoder().encodeToString(bytes));
        } catch (IOException error) {
            throw new ApiException(409, "CONFIGURATION_SCRIPT_MISSING", "Не удалось прочитать сохранённый Python-файл");
        }
    }

    /** Сохраняет машинный код ошибки и добавляет путь поля настроек. */
    private ApiException invalid(String field, String message) {
        return new ApiException(400, "INVALID_CONFIGURATION", message,
                List.of(new Violation(field, "INVALID_VALUE", message)));
    }

    /** Возвращает одинаковый ответ для чужой и отсутствующей конфигурации. */
    private ApiException notFound() {
        return new ApiException(404, "CONFIGURATION_NOT_FOUND", "Конфигурация не найдена");
    }
}

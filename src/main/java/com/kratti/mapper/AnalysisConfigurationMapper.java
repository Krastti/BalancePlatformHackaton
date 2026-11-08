package com.kratti.mapper;

import com.kratti.dto.response.AnalysisConfiguration;
import com.kratti.dto.response.AnalysisConfiguration.AnalysisField;
import com.kratti.dto.response.AnalysisConfiguration.Check;
import com.kratti.dto.response.AnalysisConfiguration.NormalizationRule;
import com.kratti.dto.response.AnalysisConfiguration.Script;
import com.kratti.dto.response.AnalysisConfiguration.Source;
import com.kratti.dto.response.AnalysisConfiguration.Stage;
import com.kratti.dto.response.DatasetConfigurationViews.Configuration;
import com.kratti.dto.response.DatasetConfigurationViews.Selection;
import com.kratti.dto.response.Violation;
import com.kratti.exception.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Открывает старые настройки в модели v2 без записи в базу или чтения файлов скриптов. */
@Component
public class AnalysisConfigurationMapper {

    /** Сохраняет порядок исполнения, источник, выбор скриптов и исходные параметры. */
    public AnalysisConfiguration fromLegacy(Configuration configuration) {
        List<Check> checks = new ArrayList<>();
        for (String file : configuration.checks()) {
            checks.add(new Check("file:" + file, true, script(file, null), null, null));
        }
        if (configuration.checkCode() != null) {
            checks.add(new Check("editor", true, script(null, configuration.checkCode()), null, null));
        }

        List<Stage> stages = List.of(
                new Stage("normalize", "NORMALIZATION",
                        configuration.normalizer() != null || configuration.normalizerCode() != null,
                        script(configuration.normalizer(), configuration.normalizerCode()), null),
                new Stage("compare", "COMPARISON", true,
                        script(configuration.procedure(), configuration.procedureCode()), null),
                new Stage("checks", "CHECKS", !checks.isEmpty(), script(null, null), null),
                new Stage("facts", "FACTS",
                        configuration.factsProcedure() != null || configuration.factsCode() != null,
                        script(configuration.factsProcedure(), configuration.factsCode()), null),
                new Stage("publish", "IMPORT", true, script(null, null), null));
        return new AnalysisConfiguration(2, configuration.id(), configuration.name(),
                new Source("localPackage", configuration.datasetId(), "sprJsonPair"), List.of(), List.of(),
                stages, List.copyOf(checks), configuration.parameters());
    }

    /** Возвращает старую форму только для настроек, которые исполнитель может применить без потерь. */
    public Selection toLegacy(AnalysisConfiguration value) {
        require(value != null, "configuration", "Передайте настройки");
        require(value.schemaVersion() == 2, "schemaVersion", "Поддерживается версия 2");
        Source source = value.source();
        require(source != null, "source", "Выберите источник");
        require("localPackage".equals(source.kind()), "source.kind", "Выберите локальный пакет");
        require("sprJsonPair".equals(source.format()), "source.format", "Выберите формат пары JSON");
        require(source.datasetId() != null, "source.datasetId", "Выберите набор");
        require(value.stages() != null && value.stages().size() == 5, "stages", "Укажите пять этапов");
        for (int i = 0; i < value.stages().size(); i++) {
            require(value.stages().get(i) != null, "stages[" + i + "]", "Укажите этап");
        }
        Script normalizer = value.stages().getFirst().script();
        Script procedure = value.stages().get(1).script();
        Script facts = value.stages().get(value.factsFirst() ? 2 : 3).script();
        require(normalizer != null && procedure != null && facts != null, "stages", "Укажите способы запуска этапов");
        require(value.checks() != null && value.checks().size() <= 41, "checks", "Укажите список проверок");
        List<String> files = new ArrayList<>();
        var ids = new HashSet<String>();
        var fileNames = new HashSet<String>();
        String editor = null;
        for (int i = 0; i < value.checks().size(); i++) {
            Check check = value.checks().get(i);
            String path = "checks[" + i + "]";
            require(check != null && check.id() != null && ids.add(check.id()), path + ".id",
                    "Укажите уникальный ID проверки");
            require(check.parameters() == null, path + ".parameters", "Параметры шаблона задаются в настройках запуска");
            if (check.rule() != null) {
                require(check.script() == null && check.id().matches("rule:[a-zA-Z0-9-]{1,80}"), path,
                        "Декларативная проверка требует ID правила и не содержит скрипт");
                validateRule(check.rule(), path + ".rule");
            } else {
                Script script = check.script();
                require(script != null, path, "Укажите скрипт проверки");
                if ("catalog".equals(script.kind())) {
                    require(script.code() == null && script.file() != null
                                    && script.file().matches("check_[^/\\\\]+\\.py")
                                    && script.file().length() <= 255
                                    && script.file().codePoints().noneMatch(Character::isISOControl)
                                    && check.id().equals("file:" + script.file()) && fileNames.add(script.file())
                                    && fileNames.size() <= 40,
                            path + ".script.file", "Выберите файл из каталога без повторов");
                    if (check.enabled()) {
                        files.add(script.file());
                    }
                } else {
                    require("inline".equals(script.kind()) && "editor".equals(check.id()) && script.file() == null
                                    && script.code() != null && !script.code().isBlank() && check.enabled()
                                    && i == value.checks().size() - 1,
                            path + ".script", "Проверка из редактора исполняется последней");
                    editor = script.code();
                }
            }
        }
        require(value.checks().size() - (editor == null ? 0 : 1) <= 40, "checks",
                "Допускается до 40 шаблонов и условий плюс одна проверка из редактора");
        var canonical = fromLegacy(new Configuration(value.id(), value.name(), source.datasetId(), files,
                procedure.file(), procedure.code(), editor, value.legacyParameters(),
                normalizer.file(), normalizer.code(), facts.file(), facts.code()));
        validateAnalysisFields(value.analysisFields(), procedure.file(), procedure.code());
        validateNormalization(value.normalization(), normalizer.file(), normalizer.code(), procedure.file(), procedure.code());
        requireSameEntries(value.stages(), withChecks(ordered(canonical, value.factsFirst()), value.checks()).stages(), "stages");
        return new Selection(value.name(), source.datasetId(), files, procedure.file(), procedure.code(), editor,
                value.legacyParameters(), normalizer.file(), normalizer.code(), facts.file(), facts.code());
    }

    /** Проверяет форму декларативной проверки и границы её условия. */
    private void validateRule(JsonNode rule, String field) {
        require(rule.isObject() && rule.path("type").isString(), field, "Выберите тип условия");
        String type = rule.path("type").asString();
        require(List.of("threshold", "range", "share", "change").contains(type), field + ".type", "Неизвестное условие");
        String attribute = rule.path("path").asString("");
        require(rule.path("path").isString() && validPath(List.of(attribute.split("\\.", -1))),
                field + ".path", "Укажите путь атрибута");
        require(rule.path("label").isString() && validBucketLabel(rule.path("label").asString()),
                field + ".label", "Укажите название проверки");
        String source = rule.path("source").asString("");
        require(List.of("BEFORE", "AFTER").contains(source) && (!"change".equals(type) || "AFTER".equals(source)),
                field + ".source", "Укажите допустимый срез");
        int fields = switch (type) {
            case "change" -> 4;
            case "range" -> 6;
            default -> 5;
        };
        require(rule.size() == fields, field, "Проверьте параметры выбранного условия");
        if ("change".equals(type)) {
            return;
        }
        BigDecimal max = ruleNumber(rule.path("max"), field + ".max");
        if ("range".equals(type)) {
            require(ruleNumber(rule.path("min"), field + ".min").compareTo(max) <= 0,
                    field + ".min", "Нижняя граница больше верхней");
        } else if ("share".equals(type)) {
            require(max.signum() >= 0 && max.compareTo(BigDecimal.ONE) <= 0, field + ".max", "Доля должна быть от 0 до 1");
        }
    }

    /** Читает десятичный порог без округления. */
    private BigDecimal ruleNumber(JsonNode node, String field) {
        require(node.isString() && node.asString().length() <= 80
                        && node.asString().matches("-?[0-9]+(\\.[0-9]+)?"),
                field, "Укажите десятичное число");
        return new BigDecimal(node.asString());
    }

    /** Сохраняет список и синхронизирует признак выполнения этапа проверок. */
    public AnalysisConfiguration withChecks(AnalysisConfiguration value, List<Check> checks) {
        boolean enabled = checks.stream().anyMatch(Check::enabled);
        List<Stage> stages = value.stages().stream().map(stage -> "CHECKS".equals(stage.type())
                ? new Stage(stage.id(), stage.type(), enabled, stage.script(), stage.parameters()) : stage).toList();
        return new AnalysisConfiguration(value.schemaVersion(), value.id(), value.name(), value.source(),
                value.normalization(), value.analysisFields(), stages, List.copyOf(checks), value.legacyParameters());
    }

    /** Проверяет правила приведения и совместимость со штатным исполнением. */
    public void validateNormalization(List<NormalizationRule> rules, String normalizer, String normalizerCode,
                                      String procedure, String procedureCode) {
        require(rules != null && rules.size() <= 100, "normalization", "Укажите до 100 правил");
        if (rules.isEmpty()) {
            return;
        }
        require(normalizer == null && normalizerCode == null && procedure == null && procedureCode == null,
                "normalization", "Табличные правила требуют штатного сравнения без отдельного скрипта");
        var paths = new HashSet<List<String>>();
        var targets = new HashSet<List<String>>();
        List<String> decision = List.of("decisionResult", "decision");
        for (int i = 0; i < rules.size(); i++) {
            NormalizationRule rule = rules.get(i);
            String field = "normalization[" + i + "]";
            require(rule != null && validPath(rule.sourcePath()) && !rule.sourcePath().isEmpty(),
                    field + ".sourcePath", "Укажите исходный путь");
            require(paths.add(rule.sourcePath()), field + ".sourcePath", "Исходный путь повторяется");
            require(validPath(rule.targetPath()), field + ".targetPath", "Укажите допустимый путь результата");
            require(rule.targetPath().isEmpty() || targets.add(rule.targetPath()),
                    field + ".targetPath", "Путь результата повторяется");
            require(rule.type() != null && List.of("keep", "string", "number", "boolean").contains(rule.type()),
                    field + ".type", "Укажите допустимый тип");
            require(rule.arrayKeys() != null && rule.arrayKeys().size() <= 8
                            && rule.arrayKeys().stream().allMatch(key -> key != null && key.length() <= 200
                                    && java.util.Arrays.stream(key.split("\\.", -1)).allMatch(this::validPart))
                            && new HashSet<>(rule.arrayKeys()).size() == rule.arrayKeys().size(),
                    field + ".arrayKeys", "Укажите уникальные относительные пути ключей массива");
            if (rule.excluded() || !rule.arrayKeys().isEmpty()) {
                require(rule.targetPath().isEmpty() && "keep".equals(rule.type())
                                && !(rule.excluded() && !rule.arrayKeys().isEmpty()),
                        field, "Исключение или настройку ключа нельзя объединять с приведением");
            }
            require(!rule.targetPath().equals(decision) && !rule.sourcePath().equals(decision)
                            && !rule.sourcePath().equals(List.of("decisionResult")),
                    field, "Путь итогового решения зарезервирован");
        }
    }

    /** Допускает ограниченный путь без неоднозначных разделителей. */
    private boolean validPath(List<String> path) {
        return path != null && path.size() <= 32 && path.stream().allMatch(this::validPart);
    }

    /** Проверяет отдельный сегмент пути. */
    private boolean validPart(String part) {
        return part != null && !part.isBlank() && part.length() <= 120 && part.indexOf('.') < 0
                && part.codePoints().noneMatch(Character::isISOControl);
    }

    /** Проверяет уникальность полей и порядок числовых границ. */
    public void validateAnalysisFields(List<AnalysisField> fields, String procedure, String procedureCode) {
        require(fields != null && fields.size() <= 100, "analysisFields", "Укажите до 100 полей");
        require(fields.isEmpty() || (procedure == null && procedureCode == null),
                "analysisFields", "Табличные поля требуют штатного сравнения");
        var paths = new HashSet<List<String>>();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            String prefix = "analysisFields[" + i + "]";
            require(field != null && validPath(field.path()) && !field.path().isEmpty() && paths.add(field.path()),
                    prefix + ".path", "Укажите уникальный путь поля");
            require(field.label() != null && !field.label().isBlank() && field.label().length() <= 120
                            && field.label().codePoints().noneMatch(Character::isISOControl),
                    prefix + ".label", "Укажите подпись до 120 символов");
            require("group".equals(field.role()), prefix + ".role", "Выберите поле группировки");
            validateBuckets(field.buckets(), prefix + ".buckets");
        }
    }

    /** Принимает возрастающие границы и необязательную открытую последнюю группу. */
    private void validateBuckets(List<JsonNode> buckets, String field) {
        require(buckets != null && buckets.size() <= 20, field, "Укажите до 20 интервалов");
        BigDecimal previous = null;
        var labels = new HashSet<String>();
        for (int i = 0; i < buckets.size(); i++) {
            JsonNode bucket = buckets.get(i);
            String path = field + "[" + i + "]";
            require(bucket != null && bucket.isObject() && bucket.size() == 2
                            && bucket.has("upper") && bucket.path("label").isString(),
                    path, "Укажите верхнюю границу и подпись");
            String label = bucket.path("label").asString();
            require(validBucketLabel(label) && labels.add(label), path + ".label", "Укажите уникальную подпись интервала");
            JsonNode upper = bucket.path("upper");
            boolean decimal = upper.isString() && upper.asString().length() <= 80
                    && upper.asString().matches("-?[0-9]+(\\.[0-9]+)?");
            require(decimal || (upper.isNull() && i == buckets.size() - 1),
                    path + ".upper", "Открытая граница допустима только у последнего интервала");
            if (decimal) {
                BigDecimal number = new BigDecimal(upper.asString());
                require(previous == null || number.compareTo(previous) > 0,
                        path + ".upper", "Верхние границы должны возрастать");
                previous = number;
            }
        }
    }

    /** Допускает подписи, пригодные для импорта в PostgreSQL. */
    private boolean validBucketLabel(String label) {
        return !label.isBlank() && label.length() <= 120 && label.codePoints().noneMatch(Character::isISOControl);
    }

    /** Добавляет проверенные правила и поля к снимку остальных настроек. */
    public AnalysisConfiguration withTables(AnalysisConfiguration canonical, List<NormalizationRule> rules,
                                            List<AnalysisField> fields) {
        return new AnalysisConfiguration(canonical.schemaVersion(), canonical.id(), canonical.name(),
                canonical.source(), List.copyOf(rules), List.copyOf(fields), canonical.stages(),
                canonical.checks(), canonical.legacyParameters());
    }

    /** Возвращает системный порядок этапов независимо от старого флага перестановки. */
    public AnalysisConfiguration ordered(AnalysisConfiguration canonical, boolean factsFirst) {
        return canonical;
    }

    /** Указывает первую строку, которую текущий исполнитель не может применить. */
    private void requireSameEntries(List<?> actual, List<?> expected, String field) {
        require(actual.size() == expected.size(), field, "Неподдерживаемый состав списка");
        for (int i = 0; i < actual.size(); i++) {
            require(expected.get(i).equals(actual.get(i)), field + "[" + i + "]",
                    "Текущий исполнитель не поддерживает эти настройки или позицию");
        }
    }

    /** Возвращает нарушение с путём поля, которое нужно исправить. */
    private void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new ApiException(400, "INVALID_CONFIGURATION", message,
                    List.of(new Violation(field, "INVALID_VALUE", message)));
        }
    }

    /** Переносит сохранённый выбор без разрешения пути к файлу на сервере. */
    private Script script(String file, String code) {
        if (file != null) {
            return new Script("catalog", file, null);
        }
        if (code != null && !code.isBlank()) {
            return new Script("inline", null, code);
        }
        return new Script("default", null, null);
    }
}

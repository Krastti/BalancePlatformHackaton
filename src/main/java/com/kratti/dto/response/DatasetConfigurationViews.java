package com.kratti.dto.response;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Описывает сохранённый выбор проверок и его копию в конкретном запуске. */
public final class DatasetConfigurationViews {
    /** Исключает создание экземпляра контейнера типов. */
    private DatasetConfigurationViews() { }

    /** Конфигурация рабочего пространства с именем, набором и упорядоченными проверками. */
    public record Configuration(UUID id, String name, UUID datasetId, List<String> checks, String procedure, String procedureCode, String checkCode, JsonNode parameters, String normalizer, String normalizerCode, String factsProcedure, String factsCode) { }

    /** Данные создания или обновления конфигурации. */
    public record Selection(String name, UUID datasetId, List<String> checks, String procedure, String procedureCode, String checkCode, JsonNode parameters, String normalizer, String normalizerCode, String factsProcedure, String factsCode) { }

    /** Закрепляет выбранную конфигурацию и байты проверок на момент постановки. */
    public record Snapshot(Configuration configuration, List<FrozenFile> files, FrozenFile procedure, FrozenFile normalizer, FrozenFile factsProcedure, AnalysisConfiguration analysis) { }

    /** Хранит исполняемые байты скрипта независимо от последующих правок файла. */
    public record FrozenFile(String name, String originalPath, String base64) { }
}

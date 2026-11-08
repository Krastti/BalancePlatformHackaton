package com.kratti.service;

import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Подключает серверные каталоги в формате пакета с манифестом и двумя срезами. */
@Component
public class LocalDatasetSourceProvider implements DatasetSourceProvider {
    private static final UUID OPEN_DATASET_ID = UUID.fromString("29fa0db9-4d71-415c-a399-c92df7059271");
    private static final String OPEN_SOURCE_RUN_ID = "RUN-20260827-0001";
    private static final int MAX_PREVIEW_JSON_BYTES = 256 * 1024;

    private final ObjectMapper json;
    private final String configuredPath;
    private final String configuredCatalog;

    /** Настраивает источники из одиночной папки и каталога пакетов. */
    public LocalDatasetSourceProvider(ObjectMapper json, @Value("${balance.dataset.path:}") String configuredPath,
                                     @Value("${balance.datasets.path:}") String configuredCatalog) {
        this.json = json;
        this.configuredPath = configuredPath;
        this.configuredCatalog = configuredCatalog;
    }

    /** Находит и проверяет локальные пакеты до регистрации. */
    @Override
    public List<DatasetSource> discover() throws IOException {
        var paths = new LinkedHashSet<Path>();
        if (!configuredPath.isBlank()) {
            paths.add(Path.of(configuredPath).toRealPath());
        }
        if (!configuredCatalog.isBlank()) {
            try (var children = Files.list(Path.of(configuredCatalog))) {
                for (Path entry : children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> Files.isRegularFile(path.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList()) {
                    paths.add(entry.toRealPath());
                }
            }
        }
        List<DatasetSource> sources = new ArrayList<>();
        for (Path path : paths) {
            sources.add(new LocalSource(readDataset(path), path));
        }
        return List.copyOf(sources);
    }

    /** Хранит метаданные и проверенный путь одного локального пакета. */
    private final class LocalSource implements DatasetSource {
        private final Dataset dataset;
        private final Path path;

        /** Создаёт адаптер зарегистрированного пакета. */
        private LocalSource(Dataset dataset, Path path) {
            this.dataset = dataset;
            this.path = path;
        }

        /** Возвращает публичное описание пакета. */
        @Override
        public Dataset dataset() {
            return dataset;
        }

        /** Возвращает корень пакета для Python. */
        @Override
        public Path executionPackage() {
            return path;
        }

        /** Читает первую пару из двух срезов и их подписи. */
        @Override
        public Sample sample() throws IOException {
            JsonNode slices = readManifest(path, true).path("srez");
            JsonNode before = slices.path("BEFORE");
            JsonNode after = slices.path("AFTER");
            Path beforePath = requireSlice(path, before.path("dir"), "BEFORE");
            Path afterPath = requireSlice(path, after.path("dir"), "AFTER");
            Path name = firstPair(beforePath, afterPath);
            String filename = name.toString();
            return new Sample(filename.substring(0, filename.length() - 5),
                    before.path("label").asString("BEFORE"), after.path("label").asString("AFTER"),
                    readPreviewText(beforePath.resolve(name)), readPreviewText(afterPath.resolve(name)));
        }

        /** Считает отпечаток локального пакета. */
        @Override
        public String fingerprint() throws IOException {
            return LocalDatasetSourceProvider.this.fingerprint(path);
        }
    }

    /** Читает манифест и вычисляет стабильный UUID с сохранением ID первого набора. */
    private Dataset readDataset(Path path) throws IOException {
        JsonNode data = readManifest(path, false);
        JsonNode count = data.path("applications");
        JsonNode identifier = data.path("run_id");
        String sourceId = identifier.asString("");
        if (!identifier.isString() || sourceId.isBlank() || !sourceId.equals(sourceId.strip())
                || sourceId.codePoints().anyMatch(Character::isISOControl) || sourceId.length() > 255 || !count.isIntegralNumber()
                || !count.canConvertToLong() || count.asLong() < 0) {
            throw new IllegalArgumentException("Некорректный манифест набора: " + path.getFileName());
        }
        JsonNode slices = data.path("srez");
        requireSlice(path, slices.path("BEFORE").path("dir"), "BEFORE");
        requireSlice(path, slices.path("AFTER").path("dir"), "AFTER");
        UUID id = OPEN_SOURCE_RUN_ID.equals(sourceId) ? OPEN_DATASET_ID : UUID.nameUUIDFromBytes(
                ("balanceplatform:source-dataset:" + sourceId).getBytes(StandardCharsets.UTF_8));
        return new Dataset(id, sourceId, sourceId, count.asLong());
    }

    /** Читает JSON манифеста и поддерживает метку UTF-8 в начале файла. */
    private JsonNode readManifest(Path path, boolean preview) throws IOException {
        Path file = path.resolve("manifest.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Манифест должен быть обычным файлом: " + path.getFileName());
        }
        return parseSourceJson(preview ? readPreviewText(file) : Files.readString(file));
    }

    /** Находит первую общую пару обычных файлов в порядке имён. */
    private Path firstPair(Path before, Path after) throws IOException {
        try (var files = Files.list(before)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .map(Path::getFileName)
                    .filter(file -> file.toString().endsWith(".json") && file.toString().length() > 5)
                    .filter(file -> Files.isRegularFile(after.resolve(file), LinkOption.NOFOLLOW_LINKS))
                    .min(Comparator.naturalOrder())
                    .orElseThrow(() -> new IOException("Нет пары ответов"));
        }
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

    /** Проверяет, что срез манифеста указывает на доступный каталог внутри пакета. */
    private Path requireSlice(Path dataset, JsonNode value, String side) throws IOException {
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("В манифесте отсутствует каталог среза " + side);
        }
        try {
            Path relative = Path.of(value.asString());
            if (relative.isAbsolute() || !relative.equals(relative.normalize())) {
                throw new IllegalArgumentException("Каталог среза должен быть задан относительно пакета: " + side);
            }
            Path path = dataset.resolve(relative).normalize();
            if (!path.startsWith(dataset) || !Files.isDirectory(path) || !path.equals(path.toRealPath())) {
                throw new IllegalArgumentException("Каталог среза должен находиться внутри пакета без ссылок: " + side);
            }
            return path;
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("Некорректный путь среза " + side, error);
        }
    }

    /** Вычисляет отпечаток манифеста, имён файлов и байтов обоих срезов. */
    private String fingerprint(Path root) throws IOException {
        MessageDigest digest = sha256();
        Path manifest = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Манифест должен быть обычным файлом");
        }
        byte[] content;
        try (InputStream input = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) {
            content = input.readAllBytes();
        }
        digest.update(sha256().digest(content));
        JsonNode slices = parseSourceJson(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString())
                .path("srez");
        for (String side : List.of("BEFORE", "AFTER")) {
            Path directory = requireSlice(root, slices.path(side).path("dir"), side);
            digest.update(side.getBytes(StandardCharsets.UTF_8));
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(item -> item.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    if (Files.isSymbolicLink(file)) {
                        throw new IOException("Ответ не должен быть символьной ссылкой");
                    }
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    byte[] name = file.getFileName().toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
                    digest.update(name);
                    digest.update(fileFingerprint(file));
                }
            }
            digest.update(new byte[Integer.BYTES]);
        }
        return HexFormat.of().formatHex(digest.digest());
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

}

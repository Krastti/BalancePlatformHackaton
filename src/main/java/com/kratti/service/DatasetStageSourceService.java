package com.kratti.service;

import com.kratti.dto.response.DatasetComparisonViews.StageName;
import com.kratti.exception.ApiException;
import com.kratti.repository.DatasetStageSourceRepository;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Сохраняет исходники, из которых запускаются процедуры фонового этапа. */
@Service
@RequiredArgsConstructor
public class DatasetStageSourceService {
    /** Имя файла и его содержимое для чтения из журнала. */
    public record Source(String name, String content) { }

    private static final Pattern PYTHON_ENCODING = Pattern.compile("^[ \t\f]*#.*?coding[:=][ \t]*([-_.a-zA-Z0-9]+)");
    private final DatasetStageSourceRepository repository;
    private final ObjectMapper json;

    /** Готовит копии Python-файлов и сохраняет их в журнале до запуска процесса. */
    public List<Path> snapshot(UUID id, StageName stage, Path directory, List<Path> paths) throws IOException {
        Files.createDirectory(directory);
        List<Path> copies = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        for (Path path : paths) {
            byte[] contents = Files.readAllBytes(path);
            String name = path.getFileName().toString();
            Path copy = Files.createDirectory(directory.resolve(Integer.toString(copies.size()))).resolve(name);
            Files.write(copy, contents);
            copies.add(copy);
            sources.add(new Source(name, display(contents)));
        }
        repository.insert(id, stage, json.writeValueAsString(sources));
        return copies;
    }

    /** Возвращает сохранённый исходник или сообщает, что у этапа его ещё нет. */
    public JsonNode source(UUID id, StageName stage) {
        String value = repository.find(id, stage)
                .orElseThrow(() -> new ApiException(404, "STAGE_SOURCE_NOT_FOUND", "У этапа нет сохранённого кода"));
        return json.readTree(value);
    }

    /** Передаёт запускателю связь между неизменяемой копией проверки и её исходным путём. */
    public String checkLocations(List<Path> originals, List<Path> copies) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (int index = 1; index < originals.size(); index++) {
            paths.put(copies.get(index).toString(), originals.get(index).toString());
        }
        return json.writeValueAsString(paths);
    }

    /** Декодирует код для журнала с учётом объявления кодировки в Python. */
    private String display(byte[] contents) {
        String header = new String(contents, 0, Math.min(contents.length, 4096), StandardCharsets.ISO_8859_1);
        if (header.startsWith("\u00ef\u00bb\u00bf")) {
            return new String(contents, StandardCharsets.UTF_8).substring(1);
        }
        String[] lines = header.split("\\R", 3);
        var marker = PYTHON_ENCODING.matcher(lines[0]);
        if (!marker.find() && lines.length > 1 && (lines[0].isBlank() || lines[0].stripLeading().startsWith("#"))) {
            marker = PYTHON_ENCODING.matcher(lines[1]);
        } else {
            marker.reset();
        }
        if (marker.find()) {
            String encoding = marker.group(1).replace('_', '-');
            if (encoding.equalsIgnoreCase("utf-8-sig")) {
                return new String(contents, StandardCharsets.UTF_8);
            }
            try {
                return new String(contents, Charset.forName(encoding));
            } catch (IllegalArgumentException unsupportedEncoding) {
                // Исполняем сохранённые байты и отображаем доступное представление.
            }
        }
        return new String(contents, StandardCharsets.UTF_8);
    }
}

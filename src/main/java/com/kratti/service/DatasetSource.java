package com.kratti.service;

import com.kratti.dto.response.DatasetComparisonViews.Dataset;
import java.io.IOException;
import java.nio.file.Path;

/** Представляет зарегистрированный источник в формате, который принимает исполнитель. */
public interface DatasetSource {
    /** Возвращает публичные метаданные источника. */
    Dataset dataset();

    /** Возвращает серверный пакет JSON для запуска Python. */
    Path executionPackage();

    /** Возвращает одну пару ответов в пределах лимита предпросмотра. */
    Sample sample() throws IOException;

    /** Вычисляет отпечаток текущего входа для проверки повторного запуска. */
    String fingerprint() throws IOException;

    /** Содержит ограниченную пару и метки срезов для предпросмотра. */
    record Sample(String applicationId, String beforeLabel, String afterLabel, String before, String after) { }
}

package com.kratti.service;

import java.io.IOException;
import java.util.List;

/** Находит источники, которые можно зарегистрировать без изменения конвейера. */
public interface DatasetSourceProvider {
    /** Возвращает доступные источники в общем формате исполнения. */
    List<DatasetSource> discover() throws IOException;
}

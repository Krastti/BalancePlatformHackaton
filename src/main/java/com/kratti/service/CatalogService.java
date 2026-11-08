package com.kratti.service;

import com.kratti.dto.request.ApplicationInput;
import com.kratti.dto.request.CreateDatasetRequest;
import com.kratti.dto.request.CreateStrategyVersionRequest;
import com.kratti.dto.response.DatasetView;
import com.kratti.dto.response.PageResponse;
import com.kratti.dto.response.StrategyView;
import com.kratti.entity.ApplicationDataset;
import com.kratti.entity.HistoricalApplication;
import com.kratti.entity.StrategyVersion;
import com.kratti.exception.ApiException;
import com.kratti.mapper.ApiMapper;
import com.kratti.repository.ApplicationDatasetRepository;
import com.kratti.repository.HistoricalApplicationRepository;
import com.kratti.repository.StrategyVersionRepository;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Загружает неизменяемые наборы и версии после проверки всего входного объекта. */
@Service
@RequiredArgsConstructor
public class CatalogService {
    private final ApplicationDatasetRepository datasets;
    private final HistoricalApplicationRepository applications;
    private final StrategyVersionRepository strategies;
    private final ThresholdStrategyExecutor executor;
    private final ApiMapper mapper;

    /** Проверяет уникальность внешних ID и атомарно сохраняет набор со всеми заявками. */
    @Transactional
    public DatasetView createDataset(CreateDatasetRequest request) {
        RequestValidation validation = new RequestValidation();
        validation.text(request.name(), "name");
        if (request.applications() == null || request.applications().isEmpty()) {
            validation.add("applications", "REQUIRED", "Нужна хотя бы одна заявка");
        } else {
            Set<String> externalIds = new HashSet<>();
            for (int index = 0; index < request.applications().size(); index++) {
                ApplicationInput item = request.applications().get(index);
                String prefix = "applications[" + index + "]";
                if (item == null) {
                    validation.add(prefix, "REQUIRED", "Заявка не может быть null");
                    continue;
                }
                validation.text(item.externalId(), prefix + ".externalId");
                validation.required(item.features(), prefix + ".features");
                if (item.externalId() != null && !externalIds.add(item.externalId())) {
                    validation.add(prefix + ".externalId", "DUPLICATE", "externalId повторяется внутри набора");
                }
            }
        }
        validation.rejectIfInvalid();
        ApplicationDataset dataset = datasets.save(ApplicationDataset.builder().name(request.name()).build());
        applications.saveAll(request.applications().stream().map(item -> HistoricalApplication.builder()
                .datasetId(dataset.getId()).externalId(item.externalId()).features(item.features()).build()).toList());
        return mapper.dataset(dataset);
    }

    /** Возвращает страницу наборов в порядке, установленном контрактом. */
    @Transactional(readOnly = true)
    public PageResponse<DatasetView> listDatasets(int page, int size) {
        return Pagination.response(datasets.findAll(Pagination.request(page, size)).map(mapper::dataset));
    }

    /** Сохраняет версию после структурной проверки пороговых правил MVP. */
    @Transactional
    public StrategyView createStrategy(CreateStrategyVersionRequest request) {
        RequestValidation validation = new RequestValidation();
        validation.text(request.strategyKey(), "strategyKey");
        validation.text(request.version(), "version");
        validation.text(request.name(), "name");
        validation.required(request.definition(), "definition");
        validation.rejectIfInvalid();
        executor.validate(request.definition());
        StrategyVersion strategy = StrategyVersion.builder().strategyKey(request.strategyKey())
                .version(request.version()).name(request.name()).definition(request.definition()).build();
        return mapper.strategy(strategies.saveAndFlush(strategy));
    }

    /** Выбирает все версии либо версии одного семейства с пагинацией. */
    @Transactional(readOnly = true)
    public PageResponse<StrategyView> listStrategies(int page, int size, String strategyKey) {
        var pageable = Pagination.request(page, size);
        if (strategyKey != null && strategyKey.isEmpty()) {
            throw new ApiException(400, "BAD_REQUEST", "strategyKey не может быть пустым");
        }
        var result = strategyKey == null ? strategies.findAll(pageable)
                : strategies.findByStrategyKey(strategyKey, pageable);
        return Pagination.response(result.map(mapper::strategy));
    }
}

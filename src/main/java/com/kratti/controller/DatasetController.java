package com.kratti.controller;

import com.kratti.dto.request.CreateDatasetRequest;
import com.kratti.dto.response.DatasetView;
import com.kratti.dto.response.PageResponse;
import com.kratti.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP-операции загрузки и выбора наборов исторических заявок. */
@RestController
@RequestMapping("/api/legacy/datasets")
@RequiredArgsConstructor
public class DatasetController {
    private final CatalogService catalog;

    /** Загружает набор целиком после проверки всех записей. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetView createDataset(@RequestBody CreateDatasetRequest request) {
        return catalog.createDataset(request);
    }

    /** Возвращает страницу наборов для выбора входных данных сравнения. */
    @GetMapping
    public PageResponse<DatasetView> listDatasets(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return catalog.listDatasets(page, size);
    }
}

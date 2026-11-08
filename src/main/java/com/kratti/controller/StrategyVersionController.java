package com.kratti.controller;

import com.kratti.dto.request.CreateStrategyVersionRequest;
import com.kratti.dto.response.PageResponse;
import com.kratti.dto.response.StrategyView;
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

/** HTTP-операции загрузки и выбора неизменяемых версий стратегий. */
@RestController
@RequestMapping("/api/strategy-versions")
@RequiredArgsConstructor
public class StrategyVersionController {
    private final CatalogService catalog;

    /** Создаёт версию с уникальной парой ключа семейства и метки версии. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StrategyView createStrategyVersion(@RequestBody CreateStrategyVersionRequest request) {
        return catalog.createStrategy(request);
    }

    /** Возвращает все версии либо ограничивает выборку одним семейством. */
    @GetMapping
    public PageResponse<StrategyView> listStrategyVersions(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String strategyKey) {
        return catalog.listStrategies(page, size, strategyKey);
    }
}

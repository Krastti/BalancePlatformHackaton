package com.kratti.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Единственный фоновый работник MVP, забирающий устойчивые задания из PostgreSQL. */
@Component
@RequiredArgsConstructor
public class ComparisonWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ComparisonWorker.class);
    private final ComparisonService comparisons;
    private final ComparisonExecutionService execution;
    private volatile boolean ready;

    /** Восстанавливает прерванные состояния до начала обработки очереди. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        comparisons.recoverInterrupted();
        ready = true;
    }

    /** Забирает один ожидающий запуск без блокировки HTTP-запроса создания. */
    @Scheduled(fixedDelayString = "${balance.worker.poll-delay-ms:1000}")
    public void poll() {
        if (!ready) {
            return;
        }
        comparisons.claimNext().ifPresent(this::execute);
    }

    /** Публикует безопасную общую ошибку после отката транзакции расчёта. */
    private void execute(UUID id) {
        try {
            execution.execute(id);
        } catch (RuntimeException error) {
            LOG.error("Не удалось выполнить сравнение {}", id, error);
            comparisons.fail(id, "EXECUTION_FAILED", "Не удалось завершить расчёт сравнения");
        }
    }
}

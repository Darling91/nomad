package com.nomad.reactor.repository;

import com.nomad.common.dto.CurrencyRate;
import com.nomad.common.dto.DeltaResponse;
import com.nomad.generated.jooq.tables.records.CurrencyRatesRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.nomad.generated.jooq.Tables.CURRENCY_RATES;
import static org.jooq.impl.DSL.field;

@Repository
@Slf4j
@RequiredArgsConstructor
public class ReactorRateRepository {

    private final DSLContext dsl;

    private final Semaphore bulkhead = new Semaphore(20);

    public Mono<CurrencyRate> save(CurrencyRate rate) {
        return Mono.fromCallable(() -> {
                    if (!bulkhead.tryAcquire(2, TimeUnit.SECONDS)) {
                        log.warn("Bulkhead full for {}/{}", rate.code(), rate.sourceId());
                        throw new RuntimeException("Bulkhead full - too many concurrent requests");
                    }
                    return rate;
                })
                .flatMap(r -> doSave(r))
                .doFinally(signal -> bulkhead.release())
                .onErrorResume(e -> {
                    if (e.getMessage().contains("Bulkhead full")) {
                        return Mono.error(new RuntimeException("Too many concurrent requests"));
                    }
                    return Mono.error(e);
                });
    }

    private Mono<CurrencyRate> doSave(CurrencyRate rate) {
        return Mono.from(dsl.selectFrom(CURRENCY_RATES)
                        .where(CURRENCY_RATES.CODE.eq(rate.code()))
                        .and(CURRENCY_RATES.SOURCE_ID.eq(rate.sourceId())))
                .flatMap(existing -> updateWithVersion(existing, rate))
                .switchIfEmpty(insertNew(rate))
                .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(50))
                        .filter(this::isRetryableException)
                        .onRetryExhaustedThrow((spec, signal) ->
                                new RuntimeException("Failed after retries: " + rate.code() + "/" + rate.sourceId())))
                .timeout(Duration.ofSeconds(5)) // Общий таймаут 5 секунд
                .doOnError(e -> log.debug("Ошибка сохранения {}/{}: {}",
                        rate.code(), rate.sourceId(), e.getMessage()));
    }

    private Mono<CurrencyRate> updateWithVersion(CurrencyRatesRecord existing, CurrencyRate newRate) {
        Long currentVersion = existing.getVersion();
        OffsetDateTime now = OffsetDateTime.now();

        return Mono.from(dsl.update(CURRENCY_RATES)
                        .set(CURRENCY_RATES.RATE, newRate.rate())
                        .set(CURRENCY_RATES.UPDATED_AT, now)
                        .set(CURRENCY_RATES.VERSION, CURRENCY_RATES.VERSION.plus(1))
                        .where(CURRENCY_RATES.CODE.eq(newRate.code()))
                        .and(CURRENCY_RATES.SOURCE_ID.eq(newRate.sourceId()))
                        .and(CURRENCY_RATES.VERSION.eq(currentVersion))
                        .returning())
                .map(this::toDto)
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Version conflict for {}/{}, returning 409", newRate.code(), newRate.sourceId());
                    return Mono.error(new OptimisticLockingFailureException("Version conflict"));
                }));
    }

    private Mono<CurrencyRate> insertNew(CurrencyRate rate) {
        return Mono.from(dsl.insertInto(CURRENCY_RATES)
                        .set(CURRENCY_RATES.CODE, rate.code())
                        .set(CURRENCY_RATES.RATE, rate.rate())
                        .set(CURRENCY_RATES.SOURCE_ID, rate.sourceId())
                        .set(CURRENCY_RATES.UPDATED_AT, OffsetDateTime.now())
                        .set(CURRENCY_RATES.VERSION, 1L)
                        .returning())
                .map(this::toDto)
                .onErrorResume(e -> {
                    if (isUniqueViolation(e)) {
                        log.debug("Unique violation for {}/{}, retrying once", rate.code(), rate.sourceId());
                        return Mono.from(dsl.selectFrom(CURRENCY_RATES)
                                        .where(CURRENCY_RATES.CODE.eq(rate.code()))
                                        .and(CURRENCY_RATES.SOURCE_ID.eq(rate.sourceId())))
                                .flatMap(existing -> updateWithVersion(existing, rate));
                    }
                    return Mono.error(e);
                });
    }

    private boolean isRetryableException(Throwable throwable) {
        return throwable instanceof OptimisticLockingFailureException ||
                throwable instanceof DeadlockLoserDataAccessException;
    }

    private boolean isUniqueViolation(Throwable e) {
        return e instanceof DataIntegrityViolationException ||
                (e.getMessage() != null && e.getMessage().contains("unique constraint"));
    }

    public Mono<DeltaResponse> calculateDelta(String code) {
        return Mono.from(dsl.select(
                        field("nbk_rate", BigDecimal.class),
                        field("xe_rate", BigDecimal.class),
                        field("delta", BigDecimal.class),
                        field("delta_percent", BigDecimal.class),
                        field("last_update_nbk", OffsetDateTime.class),
                        field("last_update_xe", OffsetDateTime.class),
                        field("status", String.class),
                        field("message", String.class)
                ).from("calculate_rate_delta_func({0})", code))
                .map(record -> DeltaResponse.builder()
                        .code(code)
                        .nbkRate(record.get("nbk_rate", BigDecimal.class))
                        .xeRate(record.get("xe_rate", BigDecimal.class))
                        .delta(record.get("delta", BigDecimal.class))
                        .deltaPercent(record.get("delta_percent", BigDecimal.class))
                        .lastUpdateNbk(toLocalDateTime(record.get("last_update_nbk", OffsetDateTime.class)))
                        .lastUpdateXe(toLocalDateTime(record.get("last_update_xe", OffsetDateTime.class)))
                        .status(record.get("status", String.class))
                        .message(record.get("message", String.class))
                        .build())
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(e -> Mono.just(DeltaResponse.builder()
                        .code(code)
                        .status("ERROR")
                        .message(e.getMessage())
                        .build()));
    }

    private java.time.LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        return odt != null ? odt.toLocalDateTime() : null;
    }

    private CurrencyRate toDto(CurrencyRatesRecord record) {
        if (record == null) return null;
        return CurrencyRate.builder()
                .code(record.getCode())
                .rate(record.getRate())
                .sourceId(record.getSourceId())
                .updatedAt(record.getUpdatedAt())
                .version(record.getVersion())
                .build();
    }
}
package com.nomad.loom.repository;

import com.nomad.common.dto.CurrencyRate;
import com.nomad.common.dto.DeltaResponse;
import com.nomad.generated.jooq.tables.records.CurrencyRatesRecord;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.nomad.generated.jooq.Tables.CURRENCY_RATES;
import static org.jooq.impl.DSL.field;

@Slf4j
@Repository
public class LoomRateRepository {

    private final DSLContext dsl;
    private final ExecutorService virtualThreadExecutor;

    public LoomRateRepository(
            @Qualifier("jooqDSLContext") DSLContext dsl,
            @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.dsl = dsl;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Transactional("jdbcTransactionManager")
    public CurrencyRate save(CurrencyRate rate) {
        try {
            return trySave(rate);
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict for {}/{}", rate.code(), rate.sourceId());
            throw e;
        }
    }

    @Transactional("jdbcTransactionManager")
    public List<CurrencyRate> saveAll(List<CurrencyRate> rates) {
        if (rates.isEmpty()) {
            return List.of();
        }

        log.debug("Batch saving {} rates with ON CONFLICT", rates.size());

        var now = OffsetDateTime.now();

        var insertStep = dsl.insertInto(CURRENCY_RATES,
                CURRENCY_RATES.CODE,
                CURRENCY_RATES.RATE,
                CURRENCY_RATES.SOURCE_ID,
                CURRENCY_RATES.UPDATED_AT,
                CURRENCY_RATES.VERSION);

        for (CurrencyRate rate : rates) {
            insertStep = insertStep.values(
                    rate.code(),
                    rate.rate(),
                    rate.sourceId(),
                    now,
                    1L
            );
        }

        insertStep.onConflict(CURRENCY_RATES.CODE, CURRENCY_RATES.SOURCE_ID)
                .doUpdate()
                .set(CURRENCY_RATES.RATE, field("excluded.rate", BigDecimal.class))
                .set(CURRENCY_RATES.UPDATED_AT, field("excluded.updated_at", OffsetDateTime.class))
                .set(CURRENCY_RATES.VERSION, CURRENCY_RATES.VERSION.plus(1))
                .execute();

        return fetchAll(rates);
    }

    public List<CurrencyRate> saveAllParallel(List<CurrencyRate> rates) {
        if (rates.isEmpty()) {
            return List.of();
        }

        log.info("💾 Сохраняем {} курсов в параллельных виртуальных потоках...", rates.size());

        List<CompletableFuture<CurrencyRate>> futures = rates.stream()
                .map(rate -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return save(rate);
                    } catch (Exception e) {
                        log.error("Ошибка сохранения {}/{}: {}",
                                rate.code(), rate.sourceId(), e.getMessage());
                        return null;
                    }
                }, virtualThreadExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(r -> r != null)
                .toList();
    }

    private List<CurrencyRate> fetchAll(List<CurrencyRate> rates) {
        if (rates.isEmpty()) {
            return List.of();
        }

        var codes = rates.stream().map(CurrencyRate::code).collect(Collectors.toSet());
        var sourceIds = rates.stream().map(CurrencyRate::sourceId).collect(Collectors.toSet());

        return dsl.selectFrom(CURRENCY_RATES)
                .where(CURRENCY_RATES.CODE.in(codes))
                .and(CURRENCY_RATES.SOURCE_ID.in(sourceIds))
                .fetch(this::toDto);
    }

    private CurrencyRate trySave(CurrencyRate rate) {
        CurrencyRate updated = tryUpdate(rate);
        if (updated != null) {
            return updated;
        }
        return tryInsert(rate);
    }

    private CurrencyRate tryUpdate(CurrencyRate rate) {
        CurrencyRatesRecord existing = dsl.selectFrom(CURRENCY_RATES)
                .where(CURRENCY_RATES.CODE.eq(rate.code()))
                .and(CURRENCY_RATES.SOURCE_ID.eq(rate.sourceId()))
                .fetchOne();

        if (existing == null) {
            return null;
        }

        Long currentVersion = existing.getVersion();

        int updated = dsl.update(CURRENCY_RATES)
                .set(CURRENCY_RATES.RATE, rate.rate())
                .set(CURRENCY_RATES.UPDATED_AT, OffsetDateTime.now())
                .set(CURRENCY_RATES.VERSION, CURRENCY_RATES.VERSION.plus(1))
                .where(CURRENCY_RATES.CODE.eq(rate.code()))
                .and(CURRENCY_RATES.SOURCE_ID.eq(rate.sourceId()))
                .and(CURRENCY_RATES.VERSION.eq(currentVersion))
                .execute();

        if (updated > 0) {
            CurrencyRatesRecord updatedRecord = dsl.selectFrom(CURRENCY_RATES)
                    .where(CURRENCY_RATES.CODE.eq(rate.code()))
                    .and(CURRENCY_RATES.SOURCE_ID.eq(rate.sourceId()))
                    .fetchOne();

            return toDto(updatedRecord);
        }

        throw new OptimisticLockingFailureException(
                String.format("Version conflict for %s/%s (expected v%d)",
                        rate.code(), rate.sourceId(), currentVersion)
        );
    }

    private CurrencyRate tryInsert(CurrencyRate rate) {
        try {
            CurrencyRatesRecord inserted = dsl.insertInto(CURRENCY_RATES)
                    .set(CURRENCY_RATES.CODE, rate.code())
                    .set(CURRENCY_RATES.RATE, rate.rate())
                    .set(CURRENCY_RATES.SOURCE_ID, rate.sourceId())
                    .set(CURRENCY_RATES.UPDATED_AT, OffsetDateTime.now())
                    .set(CURRENCY_RATES.VERSION, 1L)
                    .returning()
                    .fetchOne();

            return toDto(inserted);

        } catch (DuplicateKeyException e) {
            log.debug("Duplicate key for {}/{}, trying update", rate.code(), rate.sourceId());
            return tryUpdate(rate);
        }
    }

    public DeltaResponse calculateDelta(String code) {
        var record = dsl.select(
                        field("nbk_rate", BigDecimal.class),
                        field("xe_rate", BigDecimal.class),
                        field("delta", BigDecimal.class),
                        field("delta_percent", BigDecimal.class),
                        field("last_update_nbk", OffsetDateTime.class),
                        field("last_update_xe", OffsetDateTime.class),
                        field("status", String.class),
                        field("message", String.class)
                ).from("calculate_rate_delta_func({0})", code)
                .fetchOne();

        if (record == null) {
            return DeltaResponse.builder()
                    .code(code)
                    .status("ERROR")
                    .message("No data found")
                    .build();
        }

        return DeltaResponse.builder()
                .code(code)
                .nbkRate(record.get("nbk_rate", BigDecimal.class))
                .xeRate(record.get("xe_rate", BigDecimal.class))
                .delta(record.get("delta", BigDecimal.class))
                .deltaPercent(record.get("delta_percent", BigDecimal.class))
                .lastUpdateNbk(toLocalDateTime(record.get("last_update_nbk", OffsetDateTime.class)))
                .lastUpdateXe(toLocalDateTime(record.get("last_update_xe", OffsetDateTime.class)))
                .status(record.get("status", String.class))
                .message(record.get("message", String.class))
                .build();
    }

    public List<String> findAllCodes() {
        return dsl.selectDistinct(CURRENCY_RATES.CODE)
                .from(CURRENCY_RATES)
                .orderBy(CURRENCY_RATES.CODE)
                .fetch(CURRENCY_RATES.CODE);
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
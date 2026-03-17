package com.nomad.loom.service;

import com.nomad.common.dto.CurrencyRate;
import com.nomad.common.dto.DeltaResponse;
import com.nomad.common.parser.NbkRateParser;
import com.nomad.common.parser.XeRateParser;
import com.nomad.loom.repository.LoomRateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class LoomCurrencyService {

    private final LoomRateRepository repository;
    private final NbkRateParser nbkRateParser;
    private final XeRateParser xeRateParser;
    private final RestTemplate restTemplate;
    private final RestTemplate xeRestTemplate;
    private final ExecutorService virtualThreadExecutor;

    @Value("${app.parser.nbk.url}")
    private String nbkUrl;

    @Value("${app.parser.xe.url}")
    private String xeUrl;

    private static final List<String> XE_CURRENCIES = List.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY",
            "RUB", "TRY", "UAH", "BYN", "GEL", "AZN", "KGS", "UZS"
    );

    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final AtomicInteger nbkCount = new AtomicInteger(0);
    private final AtomicInteger xeCount = new AtomicInteger(0);

    public LoomCurrencyService(
            LoomRateRepository repository,
            NbkRateParser nbkRateParser,
            XeRateParser xeRateParser,
            @Qualifier("restTemplate") RestTemplate restTemplate,
            @Qualifier("xeRestTemplate") RestTemplate xeRestTemplate,
            @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.repository = repository;
        this.nbkRateParser = nbkRateParser;
        this.xeRateParser = xeRateParser;
        this.restTemplate = restTemplate;
        this.xeRestTemplate = xeRestTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    public CollectionResult collectAndSaveRate() {
        log.info("🚀 Начинаем сбор курсов с NBK и XE (виртуальные потоки)");
        long startTime = System.nanoTime();

        errors.clear();
        nbkCount.set(0);
        xeCount.set(0);

        CompletableFuture<List<CurrencyRate>> nbkFuture = CompletableFuture
                .supplyAsync(this::fetchNbkRates, virtualThreadExecutor)
                .whenComplete((rates, throwable) -> {
                    if (throwable != null) {
                        log.error("❌ Ошибка NBK: {}", throwable.getMessage());
                        errors.add("NBK: " + throwable.getMessage());
                    } else {
                        nbkCount.set(rates.size());
                        log.info("✅ NBK вернул {} курсов", rates.size());
                    }
                });

        CompletableFuture<List<CurrencyRate>> xeFuture = CompletableFuture
                .supplyAsync(this::fetchXeRates, virtualThreadExecutor)
                .whenComplete((rates, throwable) -> {
                    if (throwable != null) {
                        log.error("❌ Ошибка XE: {}", throwable.getMessage());
                        errors.add("XE: " + throwable.getMessage());
                    } else {
                        xeCount.set(rates.size());
                        log.info("✅ XE вернул {} курсов", rates.size());
                    }
                });

        List<CurrencyRate> allRates = new ArrayList<>();

        try {
            List<CurrencyRate> nbkRates = nbkFuture.get(30, TimeUnit.SECONDS);
            List<CurrencyRate> xeRates = xeFuture.get(30, TimeUnit.SECONDS);

            allRates.addAll(nbkRates);
            allRates.addAll(xeRates);

            log.info("📊 Получено: NBK={}, XE={}, всего={}",
                    nbkRates.size(), xeRates.size(), allRates.size());
        } catch (Exception e) {
            log.error("❌ Ошибка при получении данных: {}", e.getMessage());
            errors.add("GENERAL: " + e.getMessage());
        }

        List<CurrencyRate> savedRates = new ArrayList<>();
        if (!allRates.isEmpty()) {
            try {
                savedRates = repository.saveAll(allRates);
                log.info("✅ Сохранено {} курсов", savedRates.size());
            } catch (Exception e) {
                log.error("❌ Ошибка при batch сохранении: {}", e.getMessage());
                errors.add("BATCH_SAVE: " + e.getMessage());

                log.info("🔄 Пробуем сохранить параллельно в виртуальных потоках...");
                savedRates = repository.saveAllParallel(allRates);
            }
        }

        long duration = Duration.ofNanos(System.nanoTime() - startTime).toMillis();

        return new CollectionResult(
                nbkCount.get(),
                xeCount.get(),
                savedRates.size(),
                duration,
                LocalDateTime.now(),
                List.copyOf(errors)
        );
    }

    private List<CurrencyRate> fetchNbkRates() {
        log.info("📡 Запрос к NBK в виртуальном потоке {}...", Thread.currentThread());

        try {
            long start = System.currentTimeMillis();
            String html = restTemplate.getForObject(nbkUrl, String.class);
            List<CurrencyRate> rates = nbkRateParser.parse(html);

            log.info("✅ NBK: {} курсов за {} мс в потоке {}",
                    rates.size(), System.currentTimeMillis() - start, Thread.currentThread());

            return rates;
        } catch (Exception e) {
            log.error("❌ Ошибка NBK: {}", e.getMessage());
            throw new CompletionException(e);
        }
    }

    private List<CurrencyRate> fetchXeRates() {
        log.info("📡 Запрос к XE для {} валют в виртуальном потоке {}",
                XE_CURRENCIES.size(), Thread.currentThread());

        List<CompletableFuture<Optional<CurrencyRate>>> futures = XE_CURRENCIES.stream()
                .map(currency -> CompletableFuture
                        .supplyAsync(() -> fetchXeRateForCurrency(currency), virtualThreadExecutor)
                        .exceptionally(e -> {
                            log.warn("⚠️ Ошибка {}: {}", currency, e.getMessage());
                            errors.add("XE/" + currency + ": " + e.getMessage());
                            return Optional.empty();
                        }))
                .toList();

        List<CurrencyRate> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        log.info("✅ XE: получено {}/{} курсов", results.size(), XE_CURRENCIES.size());
        return results;
    }

    private Optional<CurrencyRate> fetchXeRateForCurrency(String currency) {
        try {
            String url = String.format(xeUrl, currency);
            String html = xeRestTemplate.getForObject(url, String.class);
            CurrencyRate rate = xeRateParser.parseCurrency(currency);

            if (rate != null) {
                log.debug("✅ {} = {} в потоке {}", currency, rate.rate(), Thread.currentThread());
                return Optional.of(rate);
            }
        } catch (Exception e) {
            log.debug("⚠️ {}: {} в потоке {}", currency, e.getMessage(), Thread.currentThread());
        }
        return Optional.empty();
    }

    public DeltaResponse calculateDelta(String code) {
        try {
            return repository.calculateDelta(code);
        } catch (Exception e) {
            log.error("❌ Ошибка расчета дельты для {}: {}", code, e.getMessage());
            return DeltaResponse.builder()
                    .code(code)
                    .status("ERROR")
                    .message(e.getMessage())
                    .build();
        }
    }

    public record CollectionResult(
            int nbkCount,
            int xeCount,
            int savedCount,
            long durationMs,
            LocalDateTime timestamp,
            List<String> errors
    ) {
        public int totalFetched() { return nbkCount + xeCount; }
        public double successRate() {
            if (totalFetched() == 0) return 0;
            return Math.round((double) savedCount / totalFetched() * 100 * 10) / 10.0;
        }
    }
}
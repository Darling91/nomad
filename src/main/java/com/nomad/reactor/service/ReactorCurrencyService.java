package com.nomad.reactor.service;

import com.nomad.common.dto.CurrencyRate;
import com.nomad.common.dto.DeltaResponse;
import com.nomad.common.parser.NbkRateParser;
import com.nomad.common.parser.XeRateParser;
import com.nomad.reactor.repository.ReactorRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReactorCurrencyService {

    private final NbkRateParser nbkRateParser;
    private final XeRateParser xeRateParser;
    private final ReactorRateRepository repository;

    private final WebClient nbkWebClient;
    private final WebClient xeWebClient;

    @Value("${app.parser.nbk.url}")
    private String nbkUrl;

    @Value("${app.parser.xe.url}")
    private String xeUrl;

    private static final List<String> XE_CURRENCIES = List.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY",
            "RUB", "TRY", "UAH", "BYN", "GEL", "AZN", "KGS", "UZS"
    );

    private final Map<String, CurrencyRate> rateCache = new ConcurrentHashMap<>();
    private final AtomicInteger successCounter = new AtomicInteger(0);
    private final AtomicInteger conflictCounter = new AtomicInteger(0);

    private final AtomicInteger xeFailures = new AtomicInteger(0);
    private volatile boolean xeCircuitOpen = false;
    private volatile long circuitOpenTime = 0;
    private static final int XE_FAILURE_THRESHOLD = 5;
    private static final long XE_CIRCUIT_TIMEOUT = 30000;

    public Mono<CollectionResult> collectAndSaveRates() {
        log.info("🚀 Начинаем сбор курсов с NBK и XE");
        long startTime = System.currentTimeMillis();

        rateCache.clear();
        successCounter.set(0);
        conflictCounter.set(0);

        Mono<List<CurrencyRate>> nbkRates = fetchNbkRates()
                .doOnSubscribe(s -> log.info("📡 Запрос к NBK..."))
                .doOnSuccess(rates -> log.info("✅ NBK вернул {} курсов", rates.size()))
                .onErrorResume(e -> {
                    log.error("❌ Ошибка NBK: {}", e.getMessage());
                    return Mono.just(List.of());
                });

        Mono<List<CurrencyRate>> xeRates = fetchXeRates()
                .doOnSubscribe(s -> log.info("📡 Запрос к XE для {} валют...", XE_CURRENCIES.size()))
                .doOnSuccess(rates -> log.info("✅ XE вернул {} курсов", rates.size()))
                .onErrorResume(e -> {
                    log.error("❌ Ошибка XE: {}", e.getMessage());
                    return Mono.just(List.of());
                });

        return Mono.zip(nbkRates, xeRates)
                .timeout(Duration.ofSeconds(25))
                .flatMap(tuple -> {
                    List<CurrencyRate> allRates = new ArrayList<>();
                    allRates.addAll(tuple.getT1());
                    allRates.addAll(tuple.getT2());

                    log.info("💾 Всего получено курсов: NBK={}, XE={}, всего={}",
                            tuple.getT1().size(), tuple.getT2().size(), allRates.size());

                    if (allRates.isEmpty()) {
                        return Mono.just(createEmptyResult(startTime));
                    }

                    return Flux.fromIterable(allRates)
                            .flatMap(this::saveRate, 3) // Уменьшил параллельность до 3
                            .collectList()
                            .map(savedRates -> new CollectionResult(
                                    tuple.getT1().size(),
                                    tuple.getT2().size(),
                                    savedRates.size(),
                                    System.currentTimeMillis() - startTime,
                                    LocalDateTime.now(),
                                    new HashMap<>(rateCache),
                                    conflictCounter.get()
                            ));
                })
                .onErrorResume(e -> {
                    log.error("❌ Критическая ошибка: {}", e.getMessage());
                    return Mono.just(createEmptyResult(startTime));
                })
                .doOnSuccess(result -> log.info(
                        "✅ Сбор завершён: сохранено {}/{} за {}мс, успех {:.1f}%, конфликтов: {}",
                        result.savedCount(), result.totalFetched(),
                        result.durationMs(), result.successRate(), result.conflictCount()));
    }

    private CollectionResult createEmptyResult(long startTime) {
        return new CollectionResult(0, 0, 0,
                System.currentTimeMillis() - startTime,
                LocalDateTime.now(), new HashMap<>(), 0);
    }

    private Mono<CurrencyRate> saveRate(CurrencyRate rate) {
        return repository.save(rate)
                .timeout(Duration.ofSeconds(3))
                .doOnNext(saved -> {
                    successCounter.incrementAndGet();
                    rateCache.put(saved.sourceId() + ":" + saved.code(), saved);
                })
                .onErrorResume(OptimisticLockingFailureException.class, e -> {
                    conflictCounter.incrementAndGet();
                    log.debug("Conflict {}/{}, skipping", rate.code(), rate.sourceId());
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.debug("Save failed {}/{}: {}", rate.code(), rate.sourceId(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<List<CurrencyRate>> fetchNbkRates() {
        return nbkWebClient.get()
                .uri(nbkUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(8))
                .map(html -> {
                    long start = System.currentTimeMillis();
                    List<CurrencyRate> rates = nbkRateParser.parse(html);
                    log.debug("Парсинг NBK за {} мс, получено {} курсов",
                            System.currentTimeMillis() - start, rates.size());
                    return rates;
                })
                .retryWhen(Retry.fixedDelay(1, Duration.ofMillis(500))
                        .filter(this::isRetryableError))
                .onErrorResume(e -> {
                    log.error("❌ NBK ошибка: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private Mono<List<CurrencyRate>> fetchXeRates() {
        if (xeCircuitOpen) {
            if (System.currentTimeMillis() - circuitOpenTime > XE_CIRCUIT_TIMEOUT) {
                xeCircuitOpen = false;
                xeFailures.set(0);
                log.info("🔄 XE circuit closed, retrying...");
            } else {
                log.warn("⚠️ XE circuit open, returning empty");
                return Mono.just(List.of());
            }
        }

        return Flux.fromIterable(XE_CURRENCIES)
                .delayElements(Duration.ofMillis(300))
                .flatMap(currency -> fetchXeRateWithRetry(currency), 2)
                .filter(Objects::nonNull)
                .collectList()
                .doOnNext(rates -> {
                    if (rates.size() < XE_CURRENCIES.size() / 2) {
                        int failures = xeFailures.incrementAndGet();
                        log.warn("⚠️ XE low success rate: {}/{}, failures: {}",
                                rates.size(), XE_CURRENCIES.size(), failures);

                        if (failures >= XE_FAILURE_THRESHOLD) {
                            xeCircuitOpen = true;
                            circuitOpenTime = System.currentTimeMillis();
                            log.error("❌ XE circuit opened after {} failures", failures);
                        }
                    } else {
                        xeFailures.set(0);
                    }
                    log.info("✅ XE: получено {}/{}", rates.size(), XE_CURRENCIES.size());
                })
                .onErrorResume(e -> {
                    log.error("❌ XE критическая ошибка: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private Mono<CurrencyRate> fetchXeRateWithRetry(String currency) {
        return Mono.defer(() ->
                        xeWebClient.get()
                                .uri(String.format(xeUrl, currency))
                                .retrieve()
                                .bodyToMono(String.class)
                                .publishOn(Schedulers.boundedElastic())
                                .map(html -> {
                                    try {
                                        CurrencyRate rate = xeRateParser.parseCurrency(currency);
                                        if (rate != null) {
                                            log.debug("✅ {} = {} KZT", currency, rate.rate());
                                            return rate;
                                        }
                                    } catch (Exception e) {
                                        log.debug("Парсинг {} failed: {}", currency, e.getMessage());
                                    }
                                    return null;
                                })
                )
                .timeout(Duration.ofSeconds(5))
                .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(200))
                        .filter(this::isXeRetryableError))
                .onErrorResume(e -> {
                    log.debug("XE {} error after retries: {}", currency, e.getMessage());
                    return Mono.empty();
                });
    }

    private boolean isRetryableError(Throwable throwable) {
        return throwable instanceof WebClientResponseException.TooManyRequests ||
                throwable instanceof WebClientResponseException.ServiceUnavailable ||
                throwable instanceof WebClientResponseException.GatewayTimeout ||
                throwable instanceof java.net.ConnectException ||
                throwable instanceof java.net.SocketTimeoutException;
    }

    private boolean isXeRetryableError(Throwable throwable) {
        return throwable instanceof java.net.SocketTimeoutException ||
                throwable instanceof java.net.ConnectException ||
                (throwable instanceof WebClientResponseException &&
                        ((WebClientResponseException) throwable).getStatusCode().is5xxServerError());
    }

    public Mono<DeltaResponse> calculateDelta(String code) {
        return repository.calculateDelta(code)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> {
                    log.debug("Delta error for {}: {}", code, e.getMessage());
                    return Mono.just(DeltaResponse.builder()
                            .code(code)
                            .status("ERROR")
                            .message(e.getMessage())
                            .build());
                });
    }

    public record CollectionResult(
            int nbkCount,
            int xeCount,
            int savedCount,
            long durationMs,
            LocalDateTime timestamp,
            Map<String, CurrencyRate> rateCache,
            int conflictCount
    ) {
        public int totalFetched() {
            return nbkCount + xeCount;
        }

        public double successRate() {
            if (totalFetched() == 0) return 0;
            return Math.round((double) savedCount / totalFetched() * 100 * 10) / 10.0;
        }
    }
}
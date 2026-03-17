package com.nomad.reactor.service;

import com.nomad.common.dto.CurrencyRate;
import com.nomad.common.dto.DeltaResponse;
import com.nomad.common.parser.NbkRateParser;
import com.nomad.common.parser.XeRateParser;
import com.nomad.reactor.repository.ReactorRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ParallelFlux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
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

    private final AtomicInteger successCounter = new AtomicInteger(0);
    private final AtomicInteger conflictCounter = new AtomicInteger(0);

    public Mono<CollectionResult> collectAndSaveRates() {
        long startTime = System.currentTimeMillis();

        return Mono.zip(
                        fetchNbkRates().subscribeOn(Schedulers.parallel()),
                        fetchXeRates().subscribeOn(Schedulers.parallel())
                )
                .timeout(Duration.ofSeconds(12)) // Уменьшили с 25 до 12 секунд
                .flatMap(tuple -> {
                    List<CurrencyRate> allRates = new ArrayList<>();
                    allRates.addAll(tuple.getT1());
                    allRates.addAll(tuple.getT2());

                    if (allRates.isEmpty()) {
                        return Mono.just(createEmptyResult(startTime));
                    }

                    return Flux.fromIterable(allRates)
                            .parallel() // Параллельное сохранение
                            .runOn(Schedulers.boundedElastic())
                            .flatMap(this::saveRate)
                            .sequential()
                            .collectList()
                            .map(savedRates -> new CollectionResult(
                                    tuple.getT1().size(),
                                    tuple.getT2().size(),
                                    savedRates.size(),
                                    System.currentTimeMillis() - startTime,
                                    LocalDateTime.now(),
                                    conflictCounter.get()
                            ));
                })
                .doOnSuccess(result ->
                        log.info("✅ Сбор завершён: {}/{} за {}мс, конфликтов: {}",
                                result.savedCount(), result.totalFetched(),
                                result.durationMs(), result.conflictCount()))
                .onErrorResume(e -> {
                    log.error("❌ Ошибка: {}", e.getMessage());
                    return Mono.just(createEmptyResult(startTime));
                });
    }

    private CollectionResult createEmptyResult(long startTime) {
        return new CollectionResult(0, 0, 0,
                System.currentTimeMillis() - startTime,
                LocalDateTime.now(), 0);
    }

    private Mono<CurrencyRate> saveRate(CurrencyRate rate) {
        return repository.save(rate)
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(e -> {
                    conflictCounter.incrementAndGet();
                    return Mono.empty();
                });
    }

    private Mono<List<CurrencyRate>> fetchNbkRates() {
        return nbkWebClient.get()
                .uri(nbkUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3)) // Уменьшили с 8 до 3 секунд
                .map(html -> {
                    long start = System.currentTimeMillis();
                    List<CurrencyRate> rates = nbkRateParser.parse(html);
                    log.info("✅ NBK: {} курсов за {}мс", rates.size(),
                            System.currentTimeMillis() - start);
                    return rates;
                })
                .onErrorResume(e -> {
                    log.warn("⚠️ NBK ошибка: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private Mono<List<CurrencyRate>> fetchXeRates() {
        return Flux.fromIterable(XE_CURRENCIES)
                .flatMap(currency -> fetchXeRate(currency), 8) // concurrency = 8
                .filter(Objects::nonNull)
                .collectList()
                .timeout(Duration.ofSeconds(6)) // Общий таймаут 6 секунд
                .doOnSuccess(rates ->
                        log.info("✅ XE: {}/{} курсов", rates.size(), XE_CURRENCIES.size()))
                .onErrorResume(e -> {
                    log.warn("⚠️ XE ошибка: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private Mono<CurrencyRate> fetchXeRate(String currency) {
        return xeWebClient.get()
                .uri(String.format(xeUrl, currency))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(2)) // Таймаут на запрос
                .map(html -> {
                    try {
                        CurrencyRate rate = xeRateParser.parseCurrency(currency);
                        if (rate != null) {
                            log.debug("✅ {} = {}", currency, rate.rate());
                            return rate;
                        }
                    } catch (Exception e) {
                        log.debug("Парсинг {} failed", currency);
                    }
                    return null;
                })
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<DeltaResponse> calculateDelta(String code) {
        return repository.calculateDelta(code)
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(e -> Mono.just(DeltaResponse.builder()
                        .code(code)
                        .status("ERROR")
                        .message(e.getMessage())
                        .build()));
    }

    public record CollectionResult(
            int nbkCount,
            int xeCount,
            int savedCount,
            long durationMs,
            LocalDateTime timestamp,
            int conflictCount
    ) {
        public int totalFetched() {
            return nbkCount + xeCount;
        }
    }
}
package com.nomad.common.parser;

import com.nomad.common.dto.CurrencyRate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class XeRateParser implements RateParser {
    private static final Logger log = LoggerFactory.getLogger(XeRateParser.class);
    private static final String SOURCE_ID = "XE";

    private static final Pattern RATE_PATTERN = Pattern.compile(
            "1(?:\\.00)?\\s+([A-Z]{3})\\s*=\\s*([0-9.,]+)\\s*KZT",
            Pattern.CASE_INSENSITIVE
    );

    private final String urlTemplate;

    public XeRateParser(@Value("${app.parser.xe.url}") String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    @Override
    public List<CurrencyRate> parse(String html) {
        throw new UnsupportedOperationException("Используйте parse(String currencyCode) вместо parse(String html)");
    }
    public CurrencyRate parseCurrency(String currencyCode) {
        String code = currencyCode.toUpperCase();
        Document document = fetchHtml(code);
        return extractRate(document, code)
                .orElseThrow(() -> new RuntimeException("Курс " + code + " не найден на XE (Regex не совпал)"));
    }

    private Document fetchHtml(String currencyCode) {
        String url = String.format(urlTemplate, currencyCode);
        log.info("Запрос к XE для валюты {}: {}", currencyCode, url);

        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(5000)
                    .get();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка подключения к XE: " + url, e);
        }
    }

    private java.util.Optional<CurrencyRate> extractRate(Document document, String targetCode) {
        String pageText = document.text();

        Matcher matcher = RATE_PATTERN.matcher(pageText);

        while (matcher.find()) {
            String foundCode = matcher.group(1);

            if (foundCode.equalsIgnoreCase(targetCode)) {
                String rateStr = matcher.group(2).replace(",", "");

                try {
                    BigDecimal rate = new BigDecimal(rateStr);

                    CurrencyRate currencyRate = CurrencyRate.builder()
                            .code(targetCode)
                            .baseCode("KZT")
                            .rate(rate)
                            .nominal(1)
                            .sourceId(SOURCE_ID)
                            .updatedAt(OffsetDateTime.now())
                            .version(1L)
                            .build();

                    log.info("✅ XE: 1 {} = {} KZT", targetCode, rate);
                    return java.util.Optional.of(currencyRate);

                } catch (NumberFormatException e) {
                    log.error("Не удалось распарсить число: {}", rateStr);
                }
            }
        }

        return java.util.Optional.empty();
    }
}
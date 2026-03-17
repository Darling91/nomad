package com.nomad.common.parser;

import com.nomad.common.dto.CurrencyRate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class NbkRateParser implements RateParser {
    private static final Logger log = LoggerFactory.getLogger(NbkRateParser.class);

    private static final String SOURCE_ID = "NBK";
    private static final String BASE_CURRENCY = "KZT";

    private static final List<String> TARGET_CURRENCIES = List.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY",
            "RUB", "TRY", "UAH", "BYN", "GEL", "AZN", "KGS", "UZS"
    );

    @Override
    public List<CurrencyRate> parse(String html) {
        List<CurrencyRate> rates = new ArrayList<>();

        if (html == null || html.isBlank()) {
            log.warn("Пустой HTML от NBK");
            return rates;
        }

        Document doc = Jsoup.parse(html);
        Element table = findCurrencyTable(doc);

        if (table == null) {
            log.warn("Таблица с курсами NBK не найдена");
            return rates;
        }

        Elements rows = table.select("tr");
        log.info("NBK: найдено {} строк", rows.size());

        for (int i = 1; i < rows.size(); i++) {
            parseRow(rows.get(i)).ifPresent(rates::add);
        }

        log.info("NBK: успешно распаршено {} курсов к {}", rates.size(), BASE_CURRENCY);
        return rates;
    }

    private Element findCurrencyTable(Document doc) {
        String[] selectors = {
                "table.table",
                "table.currency-table",
                "table:contains(USD)",
                "table:contains(Доллар)"
        };

        for (String selector : selectors) {
            Element table = doc.selectFirst(selector);
            if (table != null) {
                return table;
            }
        }
        return null;
    }

    private Optional<CurrencyRate> parseRow(Element row) {
        try {
            Elements cells = row.select("td");
            if (cells.size() < 4) {
                return Optional.empty();
            }

            String firstCell = cells.get(1).text().trim();
            String secondCell = cells.get(2).text().trim();
            String thirdCell = cells.get(3).text().trim();
            String code = extractCurrencyCode(secondCell);
            if (code == null || !TARGET_CURRENCIES.contains(code)) {
                return Optional.empty();
            }

            int nominal = extractNominal(firstCell);

            BigDecimal rate = extractRate(thirdCell);
            if (rate == null) {
                return Optional.empty();
            }

            BigDecimal finalRate = nominal > 1 ?
                    rate.divide(BigDecimal.valueOf(nominal), 6, RoundingMode.HALF_UP) :
                    rate;

            CurrencyRate currencyRate = CurrencyRate.builder()
                    .code(code)
                    .baseCode(BASE_CURRENCY)
                    .rate(finalRate)
                    .sourceId(SOURCE_ID)
                    .updatedAt(OffsetDateTime.now())
                    .version(1L)
                    .build();

            log.info("✅ NBK: {} = {} {} (номинал: {})", code, finalRate, BASE_CURRENCY, nominal);
            return Optional.of(currencyRate);

        } catch (Exception e) {
            log.debug("Ошибка парсинга строки NBK: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractCurrencyCode(String text) {
        String[] parts = text.split("/");
        return parts.length > 0 ? parts[0].trim() : null;
    }

    private int extractNominal(String text) {
        try {
            String[] parts = text.split(" ");
            return Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 1;
        }
    }

    private BigDecimal extractRate(String text) {
        try {
            String cleaned = text.replace(",", ".")
                    .replaceAll("[^0-9.]", "");
            return cleaned.isEmpty() ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
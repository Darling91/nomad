package com.nomad.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@Profile("reactor")
public class WebClientConfig {

    @Bean
    public WebClient nbkWebClient() {
        return WebClient.builder()
                .baseUrl("https://nationalbank.kz/ru/exchangerates/ezhednevnye-oficialnye-rynochnye-kursy-valyut")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; NomadRatesBot/2.0)")
                .build();
    }

    @Bean
    public WebClient xeWebClient() {
        return WebClient.builder()
                .baseUrl("https://www.xe.com/currencytables/")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .defaultHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build();
    }
}
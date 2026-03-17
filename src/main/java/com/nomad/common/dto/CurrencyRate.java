package com.nomad.common.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

@Builder
public record CurrencyRate(
        String code,
        String baseCode,
        BigDecimal rate,
        Integer nominal,
        String sourceId,
        OffsetDateTime updatedAt,
        Long version
) {
}

package com.nomad.common.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record DeltaResponse(
        String code,
        BigDecimal nbkRate,
        BigDecimal xeRate,
        BigDecimal delta,
        BigDecimal deltaPercent,
        LocalDateTime lastUpdateNbk,
        LocalDateTime lastUpdateXe,
        String status,
        String message
) {
}

package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record LivePrice(
        String symbol,
        String name,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        String currency,
        LocalDateTime updatedAt
) {}

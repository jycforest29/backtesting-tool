package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
public record RiskMetrics(
        BigDecimal sharpeRatio,
        BigDecimal sortinoRatio,
        BigDecimal maxDrawdown,          // percentage (negative)
        LocalDate maxDrawdownStart,
        LocalDate maxDrawdownEnd,
        BigDecimal annualVolatility,     // percentage
        BigDecimal annualReturn,         // percentage
        BigDecimal totalReturn,          // percentage
        BigDecimal cagr                  // Compound Annual Growth Rate
) {}

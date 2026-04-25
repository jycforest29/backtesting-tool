package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class RiskMetrics {
    private BigDecimal sharpeRatio;
    private BigDecimal sortinoRatio;
    private BigDecimal maxDrawdown;          // percentage (negative)
    private LocalDate maxDrawdownStart;
    private LocalDate maxDrawdownEnd;
    private BigDecimal annualVolatility;     // percentage
    private BigDecimal annualReturn;         // percentage
    private BigDecimal totalReturn;          // percentage
    private BigDecimal cagr;                 // Compound Annual Growth Rate
}
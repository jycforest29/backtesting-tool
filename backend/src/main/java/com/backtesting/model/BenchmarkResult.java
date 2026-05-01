package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record BenchmarkResult(
        String name,
        String symbol,
        BigDecimal totalReturn,
        BigDecimal cagr,
        BigDecimal finalValue,           // if same amount was invested
        List<PortfolioBacktestResult.PortfolioValuePoint> valueHistory
) {}

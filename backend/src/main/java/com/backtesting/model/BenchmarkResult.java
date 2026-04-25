package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class BenchmarkResult {
    private String name;
    private String symbol;
    private BigDecimal totalReturn;
    private BigDecimal cagr;
    private BigDecimal finalValue;  // if same amount was invested
    private List<PortfolioBacktestResult.PortfolioValuePoint> valueHistory;
}
package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class PortfolioBacktestResult {
    private LocalDate startDate;
    private String investmentCurrency;

    // Totals
    private BigDecimal totalInvested;
    private BigDecimal finalValue;
    private BigDecimal profitLoss;
    private BigDecimal totalReturnPercent;

    // Risk metrics
    private RiskMetrics riskMetrics;

    // Individual asset performance
    private List<AssetPerformance> assetPerformances;

    // Portfolio value history for chart
    private List<PortfolioValuePoint> valueHistory;

    // DCA info
    private boolean dcaEnabled;
    private BigDecimal dcaMonthlyAmount;
    private int dcaContributions;

    // Rebalancing info
    private RebalancePeriod rebalancePeriod;
    private int rebalanceCount;

    // Benchmark comparison
    private List<BenchmarkResult> benchmarks;

    // Tax & Fee
    private TaxFeeResult taxFeeResult;

    @Data
    @Builder
    public static class AssetPerformance {
        private String symbol;
        private String name;
        private AssetType assetType;
        private BigDecimal weight;
        private BigDecimal returnPercent;
        private BigDecimal allocated;
        private BigDecimal currentValue;
    }

    @Data
    @Builder
    public static class PortfolioValuePoint {
        private LocalDate date;
        private BigDecimal value;
        private BigDecimal invested;  // cumulative invested amount (for DCA)
    }
}
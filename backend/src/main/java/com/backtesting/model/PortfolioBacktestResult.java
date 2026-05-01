package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record PortfolioBacktestResult(
        LocalDate startDate,
        String investmentCurrency,
        // Totals
        BigDecimal totalInvested,
        BigDecimal finalValue,
        BigDecimal profitLoss,
        BigDecimal totalReturnPercent,
        // Risk metrics
        RiskMetrics riskMetrics,
        // Individual asset performance
        List<AssetPerformance> assetPerformances,
        // Portfolio value history for chart
        List<PortfolioValuePoint> valueHistory,
        // DCA info
        boolean dcaEnabled,
        BigDecimal dcaMonthlyAmount,
        int dcaContributions,
        // Rebalancing info
        RebalancePeriod rebalancePeriod,
        int rebalanceCount,
        // Benchmark comparison
        List<BenchmarkResult> benchmarks,
        // Tax & Fee
        TaxFeeResult taxFeeResult
) {

    @Builder
    public record AssetPerformance(
            String symbol,
            String name,
            AssetType assetType,
            BigDecimal weight,
            BigDecimal returnPercent,
            BigDecimal allocated,
            BigDecimal currentValue
    ) {}

    @Builder
    public record PortfolioValuePoint(
            LocalDate date,
            BigDecimal value,
            BigDecimal invested            // cumulative invested amount (for DCA)
    ) {}
}

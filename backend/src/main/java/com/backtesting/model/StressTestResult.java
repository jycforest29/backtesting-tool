package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record StressTestResult(
        String scenarioName,
        String scenarioDescription,
        List<ShockApplied> shocksApplied,
        BigDecimal portfolioValueBefore,
        BigDecimal portfolioValueAfter,
        BigDecimal portfolioChange,
        BigDecimal portfolioChangePercent,
        List<AssetImpact> assetImpacts,
        String riskLevel                 // "LOW", "MEDIUM", "HIGH", "CRITICAL"
) {

    @Builder
    public record ShockApplied(
            String factor,
            BigDecimal shockPercent
    ) {}

    @Builder
    public record AssetImpact(
            String symbol,
            String name,
            BigDecimal weight,
            BigDecimal valueBefore,
            BigDecimal valueAfter,
            BigDecimal changePercent,
            BigDecimal sensitivity        // beta to the shock factor
    ) {}
}

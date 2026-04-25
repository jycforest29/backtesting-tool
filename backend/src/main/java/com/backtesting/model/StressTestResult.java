package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class StressTestResult {
    private String scenarioName;
    private String scenarioDescription;
    private List<ShockApplied> shocksApplied;

    private BigDecimal portfolioValueBefore;
    private BigDecimal portfolioValueAfter;
    private BigDecimal portfolioChange;
    private BigDecimal portfolioChangePercent;

    private List<AssetImpact> assetImpacts;

    private String riskLevel;  // "LOW", "MEDIUM", "HIGH", "CRITICAL"

    @Data
    @Builder
    public static class ShockApplied {
        private String factor;
        private BigDecimal shockPercent;
    }

    @Data
    @Builder
    public static class AssetImpact {
        private String symbol;
        private String name;
        private BigDecimal weight;
        private BigDecimal valueBefore;
        private BigDecimal valueAfter;
        private BigDecimal changePercent;
        private BigDecimal sensitivity;  // beta to the shock factor
    }
}
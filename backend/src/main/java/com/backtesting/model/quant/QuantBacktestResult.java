package com.backtesting.model.quant;

import com.backtesting.model.BenchmarkResult;
import com.backtesting.model.PortfolioBacktestResult;
import com.backtesting.model.RiskMetrics;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class QuantBacktestResult {
    private QuantStrategyType strategyType;
    private String strategyName;
    private LocalDate startDate;
    private LocalDate endDate;

    private BigDecimal initialAmount;
    private BigDecimal finalValue;
    private BigDecimal profitLoss;
    private BigDecimal totalReturnPercent;
    private RiskMetrics riskMetrics;

    /** 월별 리밸런싱 실행 이력. */
    private List<RebalanceEvent> rebalances;

    /** 일별 자산가치 (차트용, 다운샘플됨). */
    private List<PortfolioBacktestResult.PortfolioValuePoint> valueHistory;

    private List<BenchmarkResult> benchmarks;

    /** 전략이 사용한 자산 유니버스 (표시용). */
    private List<QuantAsset> universe;

    @Data
    @Builder
    public static class RebalanceEvent {
        private LocalDate date;
        /** symbol -> weight (0.0 ~ 1.0). 현금은 "CASH" 키. */
        private Map<String, BigDecimal> weights;
        /** 시장 상태 주석 — 예: "모든 공격자산 모멘텀 > 0" 또는 "카나리아 하락 → 방어". */
        private String note;
    }
}

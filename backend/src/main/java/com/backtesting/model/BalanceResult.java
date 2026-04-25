package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class BalanceResult {
    private AssetType market;
    private BigDecimal deposit;        // 예수금
    private BigDecimal totalEvalAmount; // 총 평가금액
    private BigDecimal totalPnl;       // 총 손익
    private BigDecimal totalPnlRate;   // 총 손익률(%)
    private List<Holding> holdings;

    @Data
    @Builder
    public static class Holding {
        private String symbol;
        private String name;
        private long quantity;
        private BigDecimal avgPrice;
        private BigDecimal currentPrice;
        private BigDecimal evalAmount;
        private BigDecimal pnl;
        private BigDecimal pnlRate;
    }
}

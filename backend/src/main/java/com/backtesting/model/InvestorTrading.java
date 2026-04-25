package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class InvestorTrading {
    private String stockName;
    private String stockCode;
    private List<DailyTrading> dailyData;
    private TradingSummary summary;

    @Data
    @Builder
    public static class DailyTrading {
        private LocalDate date;
        private BigDecimal closePrice;

        // Net buying (매수-매도, 순매수) in shares
        private long individualNet;   // 개인
        private long foreignNet;      // 외국인
        private long institutionNet;  // 기관

        // Net buying in KRW (금액 기준)
        private BigDecimal individualNetAmt;
        private BigDecimal foreignNetAmt;
        private BigDecimal institutionNetAmt;

        // Cumulative net buying from start (누적 순매수)
        private long individualCumNet;
        private long foreignCumNet;
        private long institutionCumNet;
    }

    @Data
    @Builder
    public static class TradingSummary {
        // Total net over period
        private long individualTotalNet;
        private long foreignTotalNet;
        private long institutionTotalNet;

        // Amount
        private BigDecimal individualTotalAmt;
        private BigDecimal foreignTotalAmt;
        private BigDecimal institutionTotalAmt;

        // Divergence: price direction vs net buying direction
        private String dominantBuyer;   // "FOREIGN", "INSTITUTION", "INDIVIDUAL"
        private String priceDirection;  // "UP", "DOWN", "FLAT"
        private String divergenceSignal; // "BULLISH", "BEARISH", "NEUTRAL"
    }
}
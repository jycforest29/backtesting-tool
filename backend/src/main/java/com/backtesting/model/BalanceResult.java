package com.backtesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * KIS 잔고 응답 모델.
 *
 * Redis @Cacheable 대상이라 Jackson 역직렬화가 가능해야 한다.
 *  - @NoArgsConstructor: Jackson 기본 생성자 호출
 *  - @AllArgsConstructor: @Builder 가 사용
 *  - @Data: setter 로 Jackson 이 필드 채움
 * 셋 중 하나라도 빠지면 캐시 hit 시점에 SerializationException 발생 (lombok 의 함정).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResult {
    private AssetType market;
    private BigDecimal deposit;        // 예수금
    private BigDecimal totalEvalAmount; // 총 평가금액
    private BigDecimal totalPnl;       // 총 손익
    private BigDecimal totalPnlRate;   // 총 손익률(%)
    private List<Holding> holdings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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

package com.backtesting.model.quant;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 전략이 특정 시점에 생성한 타깃 포지션 시그널.
 * 백테스트와 실매매 양쪽에서 사용.
 */
@Data
@Builder
public class QuantSignal {
    private LocalDate asOfDate;
    /** symbol -> 목표 비중 (0.0 ~ 1.0). 합이 1.0 이하이면 나머지는 현금. */
    private Map<String, BigDecimal> targetWeights;
    /** 결정 사유 (로깅/UI 표시용). */
    private String rationale;
    /** 각 자산별 모멘텀 스코어 등 진단 데이터. */
    private List<ScoreRow> diagnostics;

    @Data
    @Builder
    public static class ScoreRow {
        private String symbol;
        private String label;            // 예: "SPY 13612W", "Canary EEM"
        private BigDecimal score;
        private boolean selected;
    }
}

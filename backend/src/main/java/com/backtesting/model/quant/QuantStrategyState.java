package com.backtesting.model.quant;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 특정 전략의 실매매 상태. 활성화 여부, 최근 시그널, 현재 포지션.
 */
@Data
@Builder
public class QuantStrategyState {
    private QuantStrategyType strategyType;
    private String strategyName;
    private boolean enabled;                    // 자동 실행 ON/OFF
    private BigDecimal allocatedAmount;         // 이 전략에 할당된 총 금액 (KRW)
    private LocalDateTime lastRunAt;
    private LocalDateTime nextScheduledAt;
    private QuantSignal lastSignal;
    /** 현재 보유 비중 (symbol -> weight). */
    private Map<String, BigDecimal> currentWeights;
    /** 최근 실행 로그 (최신순, 최대 50건). */
    private List<QuantExecutionLog> recentExecutions;
}

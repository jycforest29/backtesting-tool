package com.backtesting.model.quant;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 전략 1회 실행 기록. 시그널 생성 + 델타 계산 + 주문 결과.
 */
@Data
@Builder
public class QuantExecutionLog {
    private LocalDateTime executedAt;
    private QuantStrategyType strategyType;
    private ExecutionKind kind;                 // SCHEDULED / MANUAL / DRY_RUN
    private QuantSignal signal;

    /** 실제 발생한 주문들. 시그널 대비 리밸런싱 델타. */
    private List<OrderOutcome> orders;

    /** 실패 시 오류 메시지. 성공 시 null. */
    private String errorMessage;

    public enum ExecutionKind {
        SCHEDULED,    // 월말 스케줄러가 자동 실행
        MANUAL,       // UI에서 수동 트리거 (실주문)
        DRY_RUN,      // 주문 시뮬레이션 (실주문 안 함)
        LIQUIDATION   // 일임 종료 시 보유 포지션 전량 매도 (SELL-only)
    }

    @Data
    @Builder
    public static class OrderOutcome {
        private String symbol;
        private String name;
        private String side;          // BUY / SELL
        private long quantity;
        private BigDecimal price;     // 주문 당시 시장가
        private boolean success;
        private String orderNo;
        private String message;
    }
}

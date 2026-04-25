package com.backtesting.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문/체결 이벤트 — Kafka topic: trade-events
 *
 * 다운스트림 소비자:
 *  - 분석 파이프라인 (체결 기록 → 수익률 분석)
 *  - 실시간 대시보드 (Slack/이메일 외부 시스템)
 *  - 외부 리스크 시스템 (규제 보고용)
 *
 * 구조 변화 시 upcast/downcast 용이하도록 명시적 schemaVersion 포함.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeEvent {
    /** event schema 버전. 증가 시 consumer 호환성 체크. */
    @Builder.Default
    private int schemaVersion = 1;

    /** ORDER_PLACED / ORDER_FILLED / STOP_LOSS / TAKE_PROFIT / STRATEGY_REBALANCE */
    private String eventType;

    private LocalDateTime occurredAt;
    private String source;          // 예: "OcoOrderService", "QuantExecutionService"
    private String strategyType;    // 있으면 채움 (VAA, MAGIC_FORMULA 등)
    private String symbol;
    private String side;            // BUY / SELL / SIGNAL
    private long quantity;
    private BigDecimal price;
    private String orderNo;
    private String positionId;
    private boolean success;
    private String message;
}

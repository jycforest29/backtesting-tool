package com.backtesting.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 실시간 체결가 틱 이벤트 — Kafka topic: tick-events
 *
 * KIS WebSocket 틱을 Kafka로 fan-out →
 *   - OCO 엔진 consumer (손절/익절 감시)
 *   - 대시보드 WS push
 *   - 장중 이상감지 분석
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TickEvent {
    private String symbol;
    private BigDecimal price;
    private LocalDateTime timestamp;
}

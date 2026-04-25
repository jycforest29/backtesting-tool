package com.backtesting.events;

import com.backtesting.service.OcoOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka tick-events 토픽 소비자 → OCO 엔진으로 전달.
 *
 * 아키텍처 목적:
 *  - KIS WS는 여러 구독자(대시보드, OCO, 분석)에게 fan-out 필요. WS 1:1 대신 Kafka fan-out.
 *  - 이 consumer가 실패해도 다른 consumer(대시보드 등)는 영향 받지 않음.
 *
 * app.kafka.tick-consumer.enabled=true 일 때만 활성.
 * 기본 false (KisWebSocketClient가 직접 OcoOrderService 호출하는 기존 경로 유지).
 * Kafka 브로커 준비되면 토글.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.kafka.tick-consumer.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TickConsumer {

    private final OcoOrderService ocoService;

    @KafkaListener(topics = EventPublisher.TOPIC_TICK, groupId = "oco-engine")
    public void onTick(TickEvent event) {
        try {
            ocoService.onTick(event.getSymbol(), event.getPrice());
        } catch (Exception e) {
            log.error("Tick consumer error for {}: {}", event.getSymbol(), e.getMessage(), e);
        }
    }
}

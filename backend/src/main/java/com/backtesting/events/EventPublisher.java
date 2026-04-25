package com.backtesting.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 이벤트 발행자.
 * KafkaTemplate이 주입되지 않으면(= Kafka 미구성) no-op.
 * 덕분에 로컬 개발 환경에서 Kafka 없이도 앱 기동 + 동작 가능.
 */
@Slf4j
@Component
public class EventPublisher {

    public static final String TOPIC_TRADE = "trade-events";
    public static final String TOPIC_TICK = "tick-events";
    public static final String TOPIC_AUDIT = "audit-events";

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafka;

    public void publishTrade(TradeEvent event) {
        publish(TOPIC_TRADE, event.getSymbol(), event);
    }

    public void publishTick(TickEvent event) {
        publish(TOPIC_TICK, event.getSymbol(), event);
    }

    public void publishAudit(Object event) {
        publish(TOPIC_AUDIT, null, event);
    }

    private void publish(String topic, String key, Object event) {
        if (kafka == null) {
            // Kafka 미구성 — 로컬 dev 모드. 조용히 스킵.
            return;
        }
        try {
            kafka.send(topic, key, event);
        } catch (Exception e) {
            // Kafka 장애 시 주문 흐름은 영향 받지 않음 (fire-and-forget)
            log.warn("Kafka publish failed topic={}: {}", topic, e.getMessage());
        }
    }

    public boolean isKafkaAvailable() {
        return kafka != null;
    }
}

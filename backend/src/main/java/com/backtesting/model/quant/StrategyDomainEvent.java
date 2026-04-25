package com.backtesting.model.quant;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 전략 aggregate 의 도메인 이벤트.
 * Java 17 sealed interface — 컴파일러가 switch exhaustiveness 를 보장 (event 추가 시 fold 로직이 빠짐없이 갱신).
 *
 * Jackson JSON serialization 은 @type discriminator 로 구분.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StrategyDomainEvent.Enabled.class, name = "ENABLED"),
        @JsonSubTypes.Type(value = StrategyDomainEvent.Disabled.class, name = "DISABLED"),
        @JsonSubTypes.Type(value = StrategyDomainEvent.AllocationChanged.class, name = "ALLOCATION_CHANGED"),
        @JsonSubTypes.Type(value = StrategyDomainEvent.Rebalanced.class, name = "REBALANCED")
})
public sealed interface StrategyDomainEvent {

    String aggregateId();
    Instant occurredAt();
    String eventType();

    record Enabled(String aggregateId, Instant occurredAt, BigDecimal initialAllocation)
            implements StrategyDomainEvent {
        public String eventType() { return "ENABLED"; }
    }

    record Disabled(String aggregateId, Instant occurredAt, String reason)
            implements StrategyDomainEvent {
        public String eventType() { return "DISABLED"; }
    }

    record AllocationChanged(String aggregateId, Instant occurredAt,
                             BigDecimal oldAmount, BigDecimal newAmount)
            implements StrategyDomainEvent {
        public String eventType() { return "ALLOCATION_CHANGED"; }
    }

    record Rebalanced(String aggregateId, Instant occurredAt,
                      Map<String, BigDecimal> targetWeights, String rationale)
            implements StrategyDomainEvent {
        public String eventType() { return "REBALANCED"; }
    }
}

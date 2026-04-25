package com.backtesting.model.quant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 이벤트 재생으로 도달한 전략 aggregate 의 projection.
 * version == 적용된 마지막 이벤트의 sequence_number.
 * asOf == 재생 기준 시점 (null = 최신).
 */
public record StrategyAggregateState(
        String aggregateId,
        long version,
        boolean enabled,
        BigDecimal allocatedAmount,
        Map<String, BigDecimal> currentWeights,
        Instant lastRebalancedAt,
        String lastRebalanceRationale,
        Instant asOf
) {
    public static StrategyAggregateState empty(String aggregateId) {
        return new StrategyAggregateState(aggregateId, 0, false, BigDecimal.ZERO,
                Map.of(), null, null, null);
    }

    /**
     * 이벤트 적용 — 새 state 반환 (불변).
     * Java 17 호환: pattern switch 대신 instanceof 체인.
     * sealed interface 라 새 이벤트 추가 시 아래 if 사슬을 보완하지 않으면 IllegalState 발생 — 빠짐 감지.
     */
    public StrategyAggregateState apply(StrategyDomainEvent event, long newVersion) {
        if (event instanceof StrategyDomainEvent.Enabled e) {
            return new StrategyAggregateState(
                    aggregateId, newVersion, true,
                    e.initialAllocation() != null ? e.initialAllocation() : allocatedAmount,
                    currentWeights, lastRebalancedAt, lastRebalanceRationale, asOf);
        }
        if (event instanceof StrategyDomainEvent.Disabled) {
            return new StrategyAggregateState(
                    aggregateId, newVersion, false, allocatedAmount,
                    currentWeights, lastRebalancedAt, lastRebalanceRationale, asOf);
        }
        if (event instanceof StrategyDomainEvent.AllocationChanged a) {
            return new StrategyAggregateState(
                    aggregateId, newVersion, enabled, a.newAmount(),
                    currentWeights, lastRebalancedAt, lastRebalanceRationale, asOf);
        }
        if (event instanceof StrategyDomainEvent.Rebalanced r) {
            return new StrategyAggregateState(
                    aggregateId, newVersion, enabled, allocatedAmount,
                    new LinkedHashMap<>(r.targetWeights()),
                    r.occurredAt(), r.rationale(), asOf);
        }
        throw new IllegalStateException("unknown event type: " + event.getClass());
    }

    public StrategyAggregateState withAsOf(Instant t) {
        return new StrategyAggregateState(aggregateId, version, enabled, allocatedAmount,
                currentWeights, lastRebalancedAt, lastRebalanceRationale, t);
    }
}

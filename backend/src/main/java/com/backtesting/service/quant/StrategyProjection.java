package com.backtesting.service.quant;

import com.backtesting.model.quant.StrategyAggregateState;
import com.backtesting.model.quant.StrategyDomainEvent;
import com.backtesting.persistence.JsonConverter;
import com.backtesting.persistence.StrategyEventEntity;
import com.backtesting.persistence.StrategySnapshotEntity;
import com.backtesting.persistence.StrategySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 이벤트 재생으로 전략 aggregate 의 현재 state 를 구축.
 *
 * 성능 최적화:
 *  - N 이벤트마다 snapshot 저장 (SNAPSHOT_EVERY=50)
 *  - current() 는 latest snapshot 이후의 이벤트만 재생 (O(N) → O(SNAPSHOT_EVERY))
 *  - projectAt(asOf) 는 time-travel — snapshot 건너뛰고 이벤트만 occurred_at ≤ asOf 필터링 후 재생
 *    (snapshot 은 seq 기준이라 시점 기준 replay 엔 부적합)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyProjection {

    private static final int SNAPSHOT_EVERY = 50;

    private final StrategyEventStore eventStore;
    private final StrategySnapshotRepository snapshotRepo;
    private final ObjectMapper mapper = JsonConverter.mapper();

    /** 최신 state (snapshot + delta). */
    public StrategyAggregateState current(String aggregateId) {
        StrategySnapshotEntity snap = snapshotRepo
                .findTopByAggregateIdOrderBySequenceNumberDesc(aggregateId)
                .orElse(null);
        StrategyAggregateState state = snap != null
                ? deserialize(snap)
                : StrategyAggregateState.empty(aggregateId);

        List<StrategyEventEntity> events = eventStore.loadSince(aggregateId, state.version());
        for (StrategyEventEntity e : events) {
            StrategyDomainEvent domainEvent = eventStore.deserialize(e);
            state = state.apply(domainEvent, e.getSequenceNumber());
        }
        return state;
    }

    /** 특정 시점으로 time-travel. snapshot 무시 + 이벤트 occurred_at 필터. */
    public StrategyAggregateState projectAt(String aggregateId, Instant asOf) {
        StrategyAggregateState state = StrategyAggregateState.empty(aggregateId);
        List<StrategyEventEntity> events = eventStore.loadUntil(aggregateId, asOf);
        for (StrategyEventEntity e : events) {
            StrategyDomainEvent domainEvent = eventStore.deserialize(e);
            state = state.apply(domainEvent, e.getSequenceNumber());
        }
        return state.withAsOf(asOf);
    }

    /**
     * snapshot 관리 — append 성공 후 호출 권장.
     * seq % SNAPSHOT_EVERY == 0 일 때만 새 snapshot 저장.
     */
    @Transactional
    public void maybeSnapshot(String aggregateId, long currentVersion) {
        if (currentVersion == 0 || currentVersion % SNAPSHOT_EVERY != 0) return;
        boolean exists = snapshotRepo.findTopByAggregateIdOrderBySequenceNumberDesc(aggregateId)
                .map(s -> s.getSequenceNumber() >= currentVersion)
                .orElse(false);
        if (exists) return;
        StrategyAggregateState state = current(aggregateId);
        try {
            StrategySnapshotEntity snap = new StrategySnapshotEntity();
            snap.setId(UUID.randomUUID().toString());
            snap.setAggregateId(aggregateId);
            snap.setSequenceNumber(state.version());
            snap.setStateJson(mapper.writeValueAsString(state));
            snap.setCreatedAt(Instant.now());
            snapshotRepo.save(snap);
            log.debug("Snapshot saved for {} at seq={}", aggregateId, state.version());
        } catch (Exception e) {
            log.warn("Snapshot save failed for {}: {}", aggregateId, e.getMessage());
        }
    }

    private StrategyAggregateState deserialize(StrategySnapshotEntity snap) {
        try {
            return mapper.readValue(snap.getStateJson(), StrategyAggregateState.class);
        } catch (Exception e) {
            log.warn("Snapshot deserialize failed id={} — falling back to full replay", snap.getId());
            return StrategyAggregateState.empty(snap.getAggregateId());
        }
    }
}

package com.backtesting.service.quant;

import com.backtesting.model.quant.StrategyDomainEvent;
import com.backtesting.persistence.JsonConverter;
import com.backtesting.persistence.StrategyEventEntity;
import com.backtesting.persistence.StrategyEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 전략 이벤트 append-only 저장소. 핵심은 OCC(optimistic concurrency control).
 *
 * append(aggId, expectedVersion, event):
 *   - expectedVersion == 0 이면 첫 이벤트 (아무 이벤트도 아직 없어야 함)
 *   - expectedVersion == N 이면 이미 N 개 이벤트가 있어야 하고 다음 seq=N+1 을 INSERT
 *   - 동시 요청이 같은 seq 를 INSERT 시도 → UNIQUE(aggregate_id, sequence_number) 위반 →
 *     DataIntegrityViolationException → OptimisticConcurrency 로 변환
 *
 * 불변식: 이벤트는 수정/삭제 불가. projection 이 순서대로 fold.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEventStore {

    private final StrategyEventRepository repo;
    private final ObjectMapper mapper = JsonConverter.mapper();

    @Transactional
    public long append(String aggregateId, long expectedVersion, StrategyDomainEvent event) {
        long currentMax = repo.findMaxSeq(aggregateId).orElse(0L);
        if (currentMax != expectedVersion) {
            throw new OptimisticConcurrency(
                    "aggregate " + aggregateId + " is at v" + currentMax
                            + " but expected v" + expectedVersion);
        }
        long nextSeq = expectedVersion + 1;
        StrategyEventEntity e = new StrategyEventEntity();
        e.setId(UUID.randomUUID().toString());
        e.setAggregateId(aggregateId);
        e.setSequenceNumber(nextSeq);
        e.setEventType(event.eventType());
        e.setOccurredAt(event.occurredAt() != null ? event.occurredAt() : Instant.now());
        try {
            e.setPayload(mapper.writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException("event serialize failed: " + ex.getMessage(), ex);
        }
        try {
            repo.saveAndFlush(e);
        } catch (DataIntegrityViolationException dup) {
            // 체크 → 저장 사이 경쟁 조건 — UNIQUE 제약이 백스톱으로 잡아냄
            throw new OptimisticConcurrency(
                    "concurrent append detected for " + aggregateId + " seq=" + nextSeq);
        }
        return nextSeq;
    }

    public List<StrategyEventEntity> loadSince(String aggregateId, long afterSeq) {
        return repo.findByAggregateIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                aggregateId, afterSeq);
    }

    public List<StrategyEventEntity> loadUntil(String aggregateId, Instant asOf) {
        return repo.findByAggregateIdAndOccurredAtLessThanEqualOrderBySequenceNumberAsc(
                aggregateId, asOf);
    }

    public long currentVersion(String aggregateId) {
        return repo.findMaxSeq(aggregateId).orElse(0L);
    }

    public StrategyDomainEvent deserialize(StrategyEventEntity e) {
        try {
            return mapper.readValue(e.getPayload(), StrategyDomainEvent.class);
        } catch (Exception ex) {
            throw new IllegalStateException("event deserialize failed id=" + e.getId(), ex);
        }
    }

    public static class OptimisticConcurrency extends RuntimeException {
        public OptimisticConcurrency(String m) { super(m); }
    }
}

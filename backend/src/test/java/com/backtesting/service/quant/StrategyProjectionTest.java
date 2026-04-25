package com.backtesting.service.quant;

import com.backtesting.model.quant.StrategyAggregateState;
import com.backtesting.model.quant.StrategyDomainEvent;
import com.backtesting.persistence.JsonConverter;
import com.backtesting.persistence.StrategyEventEntity;
import com.backtesting.persistence.StrategyEventRepository;
import com.backtesting.persistence.StrategySnapshotEntity;
import com.backtesting.persistence.StrategySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EventStore OCC + Projection fold + time-travel + snapshot 통합 시나리오.
 * JPA repo 는 in-memory 스텁으로 대체해 순수 로직 검증.
 */
class StrategyProjectionTest {

    private StrategyEventRepository eventRepo;
    private StrategySnapshotRepository snapshotRepo;
    private StrategyEventStore store;
    private StrategyProjection projection;
    private InMemoryEventStore memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryEventStore();
        eventRepo = mock(StrategyEventRepository.class);
        snapshotRepo = mock(StrategySnapshotRepository.class);
        when(eventRepo.findMaxSeq(any())).thenAnswer(inv -> memory.maxSeq(inv.getArgument(0)));
        when(eventRepo.saveAndFlush(any())).thenAnswer(inv -> {
            StrategyEventEntity e = inv.getArgument(0);
            memory.insert(e);
            return e;
        });
        when(eventRepo.findByAggregateIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(any(), anyLong()))
                .thenAnswer(inv -> memory.loadSince(inv.getArgument(0), inv.getArgument(1)));
        when(eventRepo.findByAggregateIdAndOccurredAtLessThanEqualOrderBySequenceNumberAsc(any(), any()))
                .thenAnswer(inv -> memory.loadUntil(inv.getArgument(0), inv.getArgument(1)));
        when(snapshotRepo.findTopByAggregateIdOrderBySequenceNumberDesc(any()))
                .thenReturn(Optional.empty());

        store = new StrategyEventStore(eventRepo);
        projection = new StrategyProjection(store, snapshotRepo);
    }

    @Test
    void append_assignsMonotonicSequences() {
        long v1 = store.append("VAA", 0, enabled("VAA", new BigDecimal("1000000")));
        long v2 = store.append("VAA", 1, rebalanced("VAA", Map.of("360750", new BigDecimal("1.0"))));
        assertEquals(1, v1);
        assertEquals(2, v2);
    }

    @Test
    void append_wrongExpectedVersion_throwsConcurrency() {
        store.append("VAA", 0, enabled("VAA", BigDecimal.ONE));
        assertThrows(StrategyEventStore.OptimisticConcurrency.class,
                () -> store.append("VAA", 0, enabled("VAA", BigDecimal.TEN)));
    }

    @Test
    void append_uniqueConstraintViolation_mapsToConcurrency() {
        // doThrow 패턴: when() 은 재스터빙 시 기존 thenAnswer 를 null 인자로 호출해 NPE 나는 사이드이펙트가 있음
        doThrow(new DataIntegrityViolationException("dup")).when(eventRepo).saveAndFlush(any());
        assertThrows(StrategyEventStore.OptimisticConcurrency.class,
                () -> store.append("VAA", 0, enabled("VAA", BigDecimal.ONE)));
    }

    @Test
    void projection_foldsEventsIntoCurrentState() {
        store.append("VAA", 0, enabled("VAA", new BigDecimal("1000000")));
        store.append("VAA", 1, rebalanced("VAA", Map.of("360750", new BigDecimal("0.6"),
                                                          "069500", new BigDecimal("0.4"))));
        store.append("VAA", 2, allocationChanged("VAA",
                new BigDecimal("1000000"), new BigDecimal("2000000")));

        StrategyAggregateState state = projection.current("VAA");
        assertEquals(3, state.version());
        assertTrue(state.enabled());
        assertEquals(0, state.allocatedAmount().compareTo(new BigDecimal("2000000")));
        assertEquals(2, state.currentWeights().size());
    }

    @Test
    void timeTravel_replaysUpToAsOfOnly() {
        Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2024-02-01T00:00:00Z");
        Instant t3 = Instant.parse("2024-03-01T00:00:00Z");

        store.append("VAA", 0, new StrategyDomainEvent.Enabled("VAA", t1, new BigDecimal("1000000")));
        store.append("VAA", 1, new StrategyDomainEvent.Rebalanced("VAA", t2,
                Map.of("A", BigDecimal.ONE), "first"));
        store.append("VAA", 2, new StrategyDomainEvent.Disabled("VAA", t3, "stop"));

        // 2024-02-15 시점: enabled + first rebalance 반영, disabled 미반영
        StrategyAggregateState past = projection.projectAt("VAA",
                Instant.parse("2024-02-15T00:00:00Z"));
        assertEquals(2, past.version());
        assertTrue(past.enabled(), "Disabled 이벤트가 asOf 이후라 반영 안됨");
        assertEquals("first", past.lastRebalanceRationale());

        // 현재: disabled 까지 반영
        StrategyAggregateState now = projection.current("VAA");
        assertFalse(now.enabled());
    }

    @Test
    void disabled_thenEnabled_togglesEnabledFlag() {
        store.append("VAA", 0, enabled("VAA", new BigDecimal("1000")));
        store.append("VAA", 1, new StrategyDomainEvent.Disabled("VAA", Instant.now(), "test"));
        store.append("VAA", 2, enabled("VAA", null));

        StrategyAggregateState state = projection.current("VAA");
        assertTrue(state.enabled());
        assertEquals(3, state.version());
    }

    @Test
    void emptyAggregate_returnsEmptyState() {
        StrategyAggregateState s = projection.current("UNKNOWN");
        assertEquals(0, s.version());
        assertFalse(s.enabled());
        assertTrue(s.currentWeights().isEmpty());
    }

    @Test
    void rebalanced_updatesLastRebalancedAt() {
        Instant when = Instant.parse("2024-06-01T10:00:00Z");
        store.append("VAA", 0, new StrategyDomainEvent.Rebalanced("VAA", when,
                Map.of("X", BigDecimal.ONE), "reason"));
        StrategyAggregateState s = projection.current("VAA");
        assertEquals(when, s.lastRebalancedAt());
        assertEquals("reason", s.lastRebalanceRationale());
    }

    // ----- helpers -----

    private static StrategyDomainEvent.Enabled enabled(String agg, BigDecimal amount) {
        return new StrategyDomainEvent.Enabled(agg, Instant.now(), amount);
    }

    private static StrategyDomainEvent.Rebalanced rebalanced(String agg, Map<String, BigDecimal> w) {
        return new StrategyDomainEvent.Rebalanced(agg, Instant.now(), w, "test");
    }

    private static StrategyDomainEvent.AllocationChanged allocationChanged(
            String agg, BigDecimal oldA, BigDecimal newA) {
        return new StrategyDomainEvent.AllocationChanged(agg, Instant.now(), oldA, newA);
    }

    /** JPA 없이 테스트할 수 있도록 하는 in-memory 이벤트 저장소. UNIQUE(agg, seq) 시뮬레이트. */
    private static final class InMemoryEventStore {
        private final List<StrategyEventEntity> all = new ArrayList<>();
        private final AtomicLong idGen = new AtomicLong();

        Optional<Long> maxSeq(String agg) {
            return all.stream().filter(e -> e.getAggregateId().equals(agg))
                    .map(StrategyEventEntity::getSequenceNumber).max(Long::compareTo);
        }

        void insert(StrategyEventEntity e) {
            boolean dup = all.stream().anyMatch(x ->
                    x.getAggregateId().equals(e.getAggregateId())
                            && x.getSequenceNumber() == e.getSequenceNumber());
            if (dup) throw new DataIntegrityViolationException("unique violation");
            if (e.getId() == null) e.setId("inmem-" + idGen.incrementAndGet());
            all.add(e);
        }

        List<StrategyEventEntity> loadSince(String agg, long after) {
            return all.stream()
                    .filter(e -> e.getAggregateId().equals(agg))
                    .filter(e -> e.getSequenceNumber() > after)
                    .sorted((a, b) -> Long.compare(a.getSequenceNumber(), b.getSequenceNumber()))
                    .toList();
        }

        List<StrategyEventEntity> loadUntil(String agg, Instant asOf) {
            return all.stream()
                    .filter(e -> e.getAggregateId().equals(agg))
                    .filter(e -> !e.getOccurredAt().isAfter(asOf))
                    .sorted((a, b) -> Long.compare(a.getSequenceNumber(), b.getSequenceNumber()))
                    .toList();
        }
    }
}

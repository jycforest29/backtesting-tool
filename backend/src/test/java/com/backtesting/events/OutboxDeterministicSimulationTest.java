package com.backtesting.events;

import com.backtesting.persistence.OutboxEventEntity;
import com.backtesting.persistence.OutboxEventRepository;
import com.backtesting.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic Simulation Testing (DST) — Outbox 패턴의 at-least-once 불변식 검증.
 *
 *  불변식:
 *    committed outbox event 는 유한 시간 내에 반드시 Kafka 에 적어도 1회 도착.
 *
 *  방법:
 *    1. MutableClock + in-memory repo + fault-injecting KafkaTemplate
 *    2. N 이벤트 seed
 *    3. tick 단위로 clock 진행 + publisher.drainBatch()
 *    4. 각 seed × fault rate 조합에서 모든 event 가 maxTicks 내 전달되었는지 확인
 *
 *  여러 seed 로 반복하여 재현 가능한 카오스 시나리오 생성.
 *  버그 발견 시 seed 만 있으면 동일 경로 재현 — FoundationDB 스타일.
 */
class OutboxDeterministicSimulationTest {

    private static final int EVENT_COUNT = 20;
    /** backoff 최대 3600s, 2s tick → 극단 리트라이도 여유로 수렴하도록 2200 tick. */
    private static final int MAX_TICKS = 2200;
    private static final Duration TICK_DURATION = Duration.ofSeconds(2);

    @Test
    void allEventsEventuallyDelivered_acrossSeedsAndFaultRates() {
        long[] seeds = {1L, 42L, 2026L, 7777L, 99991L};
        // 10%·30% 는 현실적 stress, 더 높으면 backoff 최대 3600s 도달 후 MAX_TICKS 초과 위험
        double[] faultRates = {0.10, 0.30};

        for (long seed : seeds) {
            for (double faultRate : faultRates) {
                SimResult result = runSim(seed, faultRate);
                assertThat(result.sentEventIds)
                        .as("seed=%d faultRate=%.2f : 모든 event 는 maxTicks 내 전달 완료",
                                seed, faultRate)
                        .containsAll(result.expectedEventIds);
                assertThat(result.totalSendAttempts)
                        .as("seed=%d faultRate=%.2f : 실패 재시도 포함 송신 attempt > event 수",
                                seed, faultRate)
                        .isGreaterThanOrEqualTo(EVENT_COUNT);
            }
        }
    }

    @Test
    void highFaultRate_stillConverges_withEnoughTicks() {
        // 40% fault rate — 강한 stress. 2200 tick × 2초 = 73분 시뮬레이션 → 최대 backoff(3600s)도 소화.
        SimResult result = runSim(13579L, 0.4);
        assertThat(result.sentEventIds).containsAll(result.expectedEventIds);
    }

    @Test
    void zeroFault_allDeliveredQuickly() {
        SimResult result = runSim(1L, 0.0);
        assertThat(result.sentEventIds).containsAll(result.expectedEventIds);
        assertThat(result.totalSendAttempts).isEqualTo(EVENT_COUNT);
    }

    // ----- sim runner -----

    private SimResult runSim(long seed, double faultRate) {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        List<OutboxEventEntity> store = seedEvents(clock);

        OutboxEventRepository repo = inMemoryRepo(store);
        Random rng = new Random(seed);
        AtomicInteger sendCount = new AtomicInteger();

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            sendCount.incrementAndGet();
            if (rng.nextDouble() < faultRate) {
                CompletableFuture<Object> f = new CompletableFuture<>();
                f.completeExceptionally(new RuntimeException("fake kafka failure"));
                return f;
            }
            return CompletableFuture.completedFuture(null);
        });

        OutboxPublisher publisher = new OutboxPublisher(repo, clock);
        ReflectionTestUtils.setField(publisher, "kafka", kafka);
        ReflectionTestUtils.setField(publisher, "batchSize", 10);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);

        Set<String> expected = store.stream()
                .map(OutboxEventEntity::getId)
                .collect(Collectors.toCollection(HashSet::new));

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            publisher.drainBatch();
            clock.advance(TICK_DURATION);
            long unsent = store.stream().filter(e -> e.getSentAt() == null).count();
            if (unsent == 0) break;
        }

        Set<String> sent = store.stream()
                .filter(e -> e.getSentAt() != null)
                .map(OutboxEventEntity::getId)
                .collect(Collectors.toCollection(HashSet::new));

        return new SimResult(expected, sent, sendCount.get());
    }

    private List<OutboxEventEntity> seedEvents(MutableClock clock) {
        List<OutboxEventEntity> out = new ArrayList<>();
        for (int i = 0; i < EVENT_COUNT; i++) {
            OutboxEventEntity e = new OutboxEventEntity();
            e.setId("evt-" + i);
            e.setAggregateType("ORDER");
            e.setAggregateId("ord-" + i);
            e.setEventType("ORDER_PLACED");
            e.setTopic("trade-events");
            e.setPartitionKey("005930");
            e.setPayload("{\"id\":\"evt-" + i + "\",\"symbol\":\"005930\"}");
            e.setCreatedAt(clock.instant());
            e.setNextAttemptAt(clock.instant());
            e.setRetryCount(0);
            out.add(e);
        }
        return out;
    }

    private OutboxEventRepository inMemoryRepo(List<OutboxEventEntity> store) {
        OutboxEventRepository mock = mock(OutboxEventRepository.class);
        when(mock.findUnsent(any(), any(Pageable.class))).thenAnswer(inv -> {
            Instant now = inv.getArgument(0);
            Pageable page = inv.getArgument(1);
            return store.stream()
                    .filter(e -> e.getSentAt() == null)
                    .filter(e -> !e.getNextAttemptAt().isAfter(now))
                    .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                    .limit(page.getPageSize())
                    .collect(Collectors.toList());
        });
        when(mock.findById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return store.stream().filter(e -> e.getId().equals(id)).findFirst();
        });
        doAnswer(inv -> {
            OutboxEventEntity e = inv.getArgument(0);
            if (!store.contains(e)) store.add(e);
            return e;
        }).when(mock).save(any());
        when(mock.countBySentAtIsNull()).thenAnswer(inv ->
                store.stream().filter(e -> e.getSentAt() == null).count());
        return mock;
    }

    private record SimResult(Set<String> expectedEventIds, Set<String> sentEventIds, int totalSendAttempts) {}
}

package com.backtesting.events;

import com.backtesting.persistence.OutboxEventEntity;
import com.backtesting.persistence.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Outbox publisher 의 at-least-once + exponential backoff 동작 검증.
 */
class OutboxPublisherTest {

    private OutboxEventRepository repo;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> kafka;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(OutboxEventRepository.class);
        kafka = mock(KafkaTemplate.class);
        publisher = new OutboxPublisher(repo);
        ReflectionTestUtils.setField(publisher, "kafka", kafka);
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
    }

    @Test
    void publishOne_success_marksSent() {
        OutboxEventEntity e = sampleUnsent("e1");
        when(repo.findById("e1")).thenReturn(Optional.of(e));
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishOne("e1");

        assertNotNull(e.getSentAt(), "성공 시 sentAt 세팅");
        assertNull(e.getLastError());
        verify(repo).save(e);
    }

    @Test
    void publishOne_kafkaFailure_incrementsRetryAndDelays() {
        OutboxEventEntity e = sampleUnsent("e2");
        when(repo.findById("e2")).thenReturn(Optional.of(e));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        Instant beforeNext = Instant.now();
        publisher.publishOne("e2");

        assertNull(e.getSentAt());
        assertEquals(1, e.getRetryCount());
        assertNotNull(e.getLastError());
        assertTrue(e.getLastError().contains("kafka down") || e.getLastError().contains("RuntimeException"));
        assertTrue(e.getNextAttemptAt().isAfter(beforeNext), "다음 시도 시점이 현재보다 미래");
        verify(repo).save(e);
    }

    @Test
    void publishOne_alreadySent_noop() {
        OutboxEventEntity e = sampleUnsent("e3");
        e.setSentAt(Instant.now());
        when(repo.findById("e3")).thenReturn(Optional.of(e));

        publisher.publishOne("e3");

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void publishOne_backoffGrowsWithRetries() {
        OutboxEventEntity e = sampleUnsent("e4");
        e.setRetryCount(0);
        when(repo.findById("e4")).thenReturn(Optional.of(e));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("down"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        Instant t0 = Instant.now();
        publisher.publishOne("e4");
        long firstBackoffSec = java.time.Duration.between(t0, e.getNextAttemptAt()).getSeconds();

        publisher.publishOne("e4");
        long secondBackoffSec = java.time.Duration.between(Instant.now(), e.getNextAttemptAt()).getSeconds();

        assertTrue(secondBackoffSec >= firstBackoffSec,
                "재시도 횟수 증가 → 백오프 확장");
    }

    @Test
    void backlog_delegatesToRepository() {
        when(repo.countBySentAtIsNull()).thenReturn(7L);
        assertEquals(7L, publisher.backlog());
    }

    private OutboxEventEntity sampleUnsent(String id) {
        OutboxEventEntity e = new OutboxEventEntity();
        e.setId(id);
        e.setAggregateType("ORDER");
        e.setAggregateId("ord-" + id);
        e.setEventType("ORDER_PLACED");
        e.setTopic("trade-events");
        e.setPartitionKey("005930");
        e.setPayload("{\"symbol\":\"005930\",\"qty\":1}");
        e.setCreatedAt(Instant.now());
        e.setNextAttemptAt(Instant.now());
        return e;
    }
}

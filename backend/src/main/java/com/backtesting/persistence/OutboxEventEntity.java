package com.backtesting.persistence;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Transactional Outbox 이벤트.
 * 비즈니스 엔티티(OrderRecord 등)와 같은 트랜잭션에서 INSERT 되어
 * 별도 publisher worker 가 Kafka 로 옮겨 at-least-once 발행 보장.
 *
 * 인덱스:
 *  - (sent_at, next_attempt_at) — unsent 폴링 쿼리 지원
 *
 * 주의: 소비자는 중복 수신 가정하고 eventId(=id) 기준 dedup 필요.
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "ix_outbox_unsent", columnList = "sent_at,next_attempt_at")
})
@Data
public class OutboxEventEntity {

    @Id
    @Column(length = 36)
    private String id;                  // UUID — consumer dedup key

    @Column(nullable = false, length = 32)
    private String aggregateType;       // ORDER / POSITION / ...

    @Column(nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 64)
    private String eventType;           // ORDER_PLACED 등

    @Column(nullable = false, length = 64)
    private String topic;

    @Column(length = 64)
    private String partitionKey;

    @Lob
    @Column(nullable = false)
    private String payload;             // JSON 직렬화된 원본 이벤트

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;             // null = 미발행

    @Column(nullable = false)
    private Instant nextAttemptAt;      // 재시도 스케줄 (exponential backoff)

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(length = 500)
    private String lastError;

    @Version
    private Long version;               // 낙관적 락 — publisher 중복 처리 방지
}

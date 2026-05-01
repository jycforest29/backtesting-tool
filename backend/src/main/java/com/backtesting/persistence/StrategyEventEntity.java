package com.backtesting.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 전략 aggregate 의 도메인 이벤트 append-only 로그.
 *
 * (aggregate_id, sequence_number) UNIQUE → OCC(optimistic concurrency).
 * 동시에 같은 expectedVersion 으로 append 시도 시 한 명만 성공, 나머지는 UNIQUE 위반 예외.
 *
 * 불변식:
 *  - 삭제·수정 금지 (append-only)
 *  - sequence_number 는 aggregate 내에서 1부터 빈틈없이 증가
 */
@Entity
@Table(name = "strategy_event",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_strategy_event_agg_seq",
               columnNames = {"aggregate_id", "sequence_number"}),
       indexes = {
               @Index(name = "ix_strategy_event_agg_occurred",
                      columnList = "aggregate_id,occurred_at")
       })
@Getter
@Setter
@ToString(of = {"id", "aggregateId", "sequenceNumber", "eventType"})
public class StrategyEventEntity extends AbstractEntity<String> {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}

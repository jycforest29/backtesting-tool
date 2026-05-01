package com.backtesting.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 전략 상태의 주기적 snapshot. sequence_number 기준 fold 결과를 직렬화해 보관.
 * rebuild 시 latest snapshot 이후의 이벤트만 재생 → 긴 히스토리에서도 일정한 성능.
 */
@Entity
@Table(name = "strategy_snapshot",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_strategy_snapshot_agg_seq",
               columnNames = {"aggregate_id", "sequence_number"}))
@Getter
@Setter
@ToString(of = {"id", "aggregateId", "sequenceNumber"})
public class StrategySnapshotEntity extends AbstractEntity<String> {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Lob
    @Column(nullable = false)
    private String stateJson;

    @Column(nullable = false)
    private Instant createdAt;
}

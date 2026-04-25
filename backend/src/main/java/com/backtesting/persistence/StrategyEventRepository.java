package com.backtesting.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StrategyEventRepository extends JpaRepository<StrategyEventEntity, String> {

    @Query("SELECT MAX(e.sequenceNumber) FROM StrategyEventEntity e WHERE e.aggregateId = :id")
    Optional<Long> findMaxSeq(@Param("id") String aggregateId);

    List<StrategyEventEntity> findByAggregateIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            String aggregateId, long afterSeq);

    List<StrategyEventEntity> findByAggregateIdAndOccurredAtLessThanEqualOrderBySequenceNumberAsc(
            String aggregateId, Instant asOf);
}

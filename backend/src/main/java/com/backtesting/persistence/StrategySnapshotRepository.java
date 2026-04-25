package com.backtesting.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StrategySnapshotRepository extends JpaRepository<StrategySnapshotEntity, String> {
    Optional<StrategySnapshotEntity> findTopByAggregateIdOrderBySequenceNumberDesc(String aggregateId);
}

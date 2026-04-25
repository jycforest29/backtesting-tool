package com.backtesting.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    Optional<IdempotencyRecordEntity> findByKey(String key);

    long deleteByExpiresAtBefore(Instant cutoff);
}

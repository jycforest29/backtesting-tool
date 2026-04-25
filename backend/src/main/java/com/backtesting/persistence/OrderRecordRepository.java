package com.backtesting.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRecordRepository extends JpaRepository<OrderRecordEntity, String> {
    Optional<OrderRecordEntity> findByIdempotencyKey(String key);
}

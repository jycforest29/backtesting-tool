package com.backtesting.persistence;

import com.backtesting.model.quant.QuantStrategyType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StrategyExecutionRepository extends JpaRepository<StrategyExecutionEntity, Long> {

    /** 특정 전략 실행 이력 최근순. */
    List<StrategyExecutionEntity> findByStrategyTypeOrderByExecutedAtDesc(
            QuantStrategyType strategyType, Pageable pageable);

    /** 오래된 기록 정리용. */
    long deleteByExecutedAtBefore(LocalDateTime threshold);
}

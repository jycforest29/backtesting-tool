package com.backtesting.persistence;

import com.backtesting.model.quant.QuantStrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StrategyConfigRepository extends JpaRepository<StrategyConfigEntity, QuantStrategyType> {
    List<StrategyConfigEntity> findByEnabledTrue();
}

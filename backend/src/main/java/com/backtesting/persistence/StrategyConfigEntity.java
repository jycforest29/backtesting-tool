package com.backtesting.persistence;

import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 전략별 설정/최신 상태 (StrategyType 당 1행).
 * 실행 이력은 별도 StrategyExecutionEntity에 시계열로.
 */
@Entity
@Table(name = "strategy_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfigEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private QuantStrategyType strategyType;

    private boolean enabled;
    private BigDecimal allocatedAmount;

    // 전략 파라미터 (null이면 전략 기본값)
    private Integer topN;
    private Integer momentumMonths;
    private Integer smaMonths;

    /** 사용자가 지정한 커스텀 유니버스. null이면 전략 default 사용. */
    @Convert(converter = JsonConverters.QuantAssetList.class)
    @Column(columnDefinition = "CLOB")
    private List<QuantAsset> customUniverse;

    /** 마지막 수행한 리밸런싱 날짜 — 중복 실행 방지 핵심 키. */
    private LocalDate lastRebalanceDate;

    /** 마지막 실행 시각 (MANUAL/DRY_RUN 포함). */
    private LocalDateTime lastRunAt;

    /** 최근 시그널 — UI 표시 및 상태 복원용. */
    @Convert(converter = JsonConverters.QuantSignalConv.class)
    @Column(columnDefinition = "CLOB")
    private QuantSignal lastSignal;

    /** 현재 실행 중인 비중. */
    @Convert(converter = JsonConverters.StringBigDecimalMap.class)
    @Column(columnDefinition = "CLOB")
    private Map<String, BigDecimal> currentWeights;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() { this.updatedAt = LocalDateTime.now(); }
}

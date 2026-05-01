package com.backtesting.persistence;

import com.backtesting.model.quant.QuantExecutionLog;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 전략 실행 이력 한 건. 시간역순 조회로 UI에 표시.
 *
 * kind + executedAt + strategyType 조합이 사실상 키 역할.
 * 같은 날짜+스케줄 중복 실행 방지는 서비스 레벨에서 StrategyConfigEntity.lastRebalanceDate로.
 */
@Entity
@Table(name = "strategy_executions",
       indexes = {
         @Index(name = "idx_exec_strategy_time", columnList = "strategyType, executedAt DESC")
       })
@Getter
@Setter
@ToString(of = {"id", "strategyType", "kind", "executedAt"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyExecutionEntity extends AbstractEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private QuantStrategyType strategyType;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private QuantExecutionLog.ExecutionKind kind;

    @Column(nullable = false)
    private LocalDateTime executedAt;

    @Convert(converter = JsonConverters.QuantSignalConv.class)
    @Column(columnDefinition = "CLOB")
    private QuantSignal signal;

    @Convert(converter = JsonConverters.OrderOutcomeList.class)
    @Column(columnDefinition = "CLOB")
    private List<QuantExecutionLog.OrderOutcome> orders;

    @Column(length = 2048)
    private String errorMessage;

    // ========== 도메인 ↔ 엔티티 ==========

    public static StrategyExecutionEntity fromDomain(QuantExecutionLog e) {
        return StrategyExecutionEntity.builder()
                .strategyType(e.getStrategyType())
                .kind(e.getKind())
                .executedAt(e.getExecutedAt())
                .signal(e.getSignal())
                .orders(e.getOrders())
                .errorMessage(e.getErrorMessage())
                .build();
    }

    public QuantExecutionLog toDomain() {
        return QuantExecutionLog.builder()
                .executedAt(executedAt)
                .strategyType(strategyType)
                .kind(kind)
                .signal(signal)
                .orders(orders)
                .errorMessage(errorMessage)
                .build();
    }
}

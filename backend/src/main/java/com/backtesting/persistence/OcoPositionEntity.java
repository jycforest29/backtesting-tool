package com.backtesting.persistence;

import com.backtesting.model.OcoPosition;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OCO 포지션 영속 엔티티.
 * 도메인 모델(OcoPosition)과 1:1로 매핑. toDomain()/fromDomain()으로 변환.
 *
 * 저장 시점: register(), 상태변화(activate/exit/cancel), 매수 시도 실패 기록.
 * 복원 시점: OcoOrderService 기동 시 ACTIVE/PARTIALLY_CLOSED/PENDING_* 전부 로드.
 */
@Entity
@Table(name = "oco_positions")
@Getter
@Setter
@ToString(of = {"id", "symbol", "status", "remainingQuantity"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcoPositionEntity extends AbstractEntity<String> {

    @Id
    private String id;

    @Column(nullable = false)
    private String symbol;
    private String name;

    private long totalQuantity;
    private long remainingQuantity;
    private BigDecimal entryPrice;
    private BigDecimal stopLossPrice;
    private double stopLossPercent;

    private String entryType;
    private BigDecimal triggerPrice;
    private String entryPriceRequested;
    private int buyAttempts;

    @Column(length = 1024)
    private String lastBuyFailReason;

    /** 본주문 KIS 주문번호 — 체결 확인에 사용. */
    private String mainOrderNo;

    /**
     * 본주문 제출 전/후 추적용 클라이언트 idempotency 키.
     * 네트워크 타임아웃 시 KIS 미체결 주문 조회로 실제 도달 여부 확인.
     */
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private OcoPosition.OcoStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime activatedAt;
    private LocalDateTime closedAt;
    private String closeReason;

    @Convert(converter = JsonConverters.TakeProfitList.class)
    @Column(columnDefinition = "CLOB")
    private List<OcoPosition.TakeProfitLegState> takeProfit;

    /** 마지막 갱신 시각 — 디버깅·감사용. JPA @PreUpdate로 자동 관리. */
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() { this.updatedAt = LocalDateTime.now(); }

    // ========== 도메인 ↔ 엔티티 ==========

    public static OcoPositionEntity fromDomain(OcoPosition p) {
        return OcoPositionEntity.builder()
                .id(p.getId())
                .symbol(p.getSymbol())
                .name(p.getName())
                .totalQuantity(p.getTotalQuantity())
                .remainingQuantity(p.getRemainingQuantity())
                .entryPrice(p.getEntryPrice())
                .stopLossPrice(p.getStopLossPrice())
                .stopLossPercent(p.getStopLossPercent())
                .entryType(p.getEntryType())
                .triggerPrice(p.getTriggerPrice())
                .entryPriceRequested(p.getEntryPriceRequested())
                .buyAttempts(p.getBuyAttempts())
                .lastBuyFailReason(p.getLastBuyFailReason())
                .mainOrderNo(p.getMainOrderNo())
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .activatedAt(p.getActivatedAt())
                .closedAt(p.getClosedAt())
                .closeReason(p.getCloseReason())
                .takeProfit(p.getTakeProfit() == null ? null : new ArrayList<>(p.getTakeProfit()))
                .build();
    }

    public OcoPosition toDomain() {
        return OcoPosition.builder()
                .id(id)
                .symbol(symbol)
                .name(name)
                .totalQuantity(totalQuantity)
                .remainingQuantity(remainingQuantity)
                .entryPrice(entryPrice)
                .stopLossPrice(stopLossPrice)
                .stopLossPercent(stopLossPercent)
                .entryType(entryType)
                .triggerPrice(triggerPrice)
                .entryPriceRequested(entryPriceRequested)
                .buyAttempts(buyAttempts)
                .lastBuyFailReason(lastBuyFailReason)
                .mainOrderNo(mainOrderNo)
                .status(status)
                .createdAt(createdAt)
                .activatedAt(activatedAt)
                .closedAt(closedAt)
                .closeReason(closeReason)
                .takeProfit(takeProfit == null ? null : new ArrayList<>(takeProfit))
                .build();
    }
}

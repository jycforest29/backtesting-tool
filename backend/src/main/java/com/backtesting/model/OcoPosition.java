package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * OCO 포지션 상태 스냅샷. 서버 메모리 저장.
 */
@Data
@Builder
public class OcoPosition {
    private String id;                   // 내부 식별자 (UUID)
    private String symbol;
    private String name;                 // 조회 편의
    private long totalQuantity;          // 본주문 수량
    private long remainingQuantity;      // 아직 청산 안 된 수량
    private BigDecimal entryPrice;       // 체결된 평균단가 (체결 확인 후 업데이트)
    private BigDecimal stopLossPrice;    // 계산된 손절가
    private double stopLossPercent;
    // 조건부 매수 관련
    private String entryType;            // LIMIT / MARKET / BREAKOUT_ABOVE / BREAKOUT_BELOW
    private BigDecimal triggerPrice;     // BREAKOUT_* 경우 감시할 가격
    private String entryPriceRequested;  // LIMIT 경우 사용자가 낸 지정가 (참고용)
    private int buyAttempts;             // 자금 부족 등으로 매수 재시도한 횟수
    private String lastBuyFailReason;    // 마지막 매수 시도 실패 사유
    private List<TakeProfitLegState> takeProfit;
    private String mainOrderNo;          // 본주문 KIS 주문번호
    private OcoStatus status;            // 상태 enum
    private LocalDateTime createdAt;
    private LocalDateTime activatedAt;   // 본주문 체결 확인된 시각
    private LocalDateTime closedAt;
    private String closeReason;          // STOP_LOSS | TAKE_PROFIT_ALL | MANUAL_CANCEL | ...

    @Data
    @Builder
    public static class TakeProfitLegState {
        private double percent;
        private double quantityFraction;
        private long plannedQuantity;
        private long executedQuantity;
        private BigDecimal triggerPrice;
        private boolean triggered;
        private String orderNo;
    }

    public enum OcoStatus {
        PENDING_ENTRY,   // 조건부 매수 트리거 대기 (WS로 가격 감시)
        PENDING_FILL,    // 본주문 접수 후 체결 대기
        ACTIVE,          // 본주문 체결 완료, 감시 중
        PARTIALLY_CLOSED,// 익절 일부 실행됨
        CLOSED,          // 전량 청산 (손절 or 익절 완료)
        CANCELLED,       // 수동 취소
        FAILED           // 본주문 실패 등
    }
}

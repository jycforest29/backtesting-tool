package com.backtesting.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/**
 * OCO(One-Cancels-Other) 주문 요청.
 * 본주문(매수 지정가) + 손절가 + 다단계 익절 구성을 한 번에 등록.
 * 국장 단타 전용.
 */
@Data
public class OcoRequest {

    @Pattern(regexp = "^\\d{6}$", message = "symbol 은 6자리 국내 종목코드여야 합니다")
    private String symbol;

    @Positive(message = "quantity 는 1 이상이어야 합니다")
    private long quantity;

    /**
     * 본주문 방식.
     *  LIMIT / MARKET   : 즉시 매수
     *  BREAKOUT_ABOVE   : 현재가가 triggerPrice 이상으로 올라오면 시장가 매수
     *  BREAKOUT_BELOW   : 현재가가 triggerPrice 이하로 내려오면 triggerPrice 지정가 매수
     *  EXISTING_HOLDING : 이미 보유 중인 주식에 손절/익절 조건만 연결 (매수 건너뜀)
     */
    @Pattern(regexp = "^(LIMIT|MARKET|BREAKOUT_ABOVE|BREAKOUT_BELOW|EXISTING_HOLDING)$",
            message = "entryType 은 LIMIT/MARKET/BREAKOUT_ABOVE/BREAKOUT_BELOW/EXISTING_HOLDING 중 하나여야 합니다")
    private String entryType;

    /**
     * LIMIT : 본주문 지정가
     * EXISTING_HOLDING : (선택) 손절/익절 계산 기준가. null이면 KIS 잔고의 평단가 사용.
     */
    @Size(max = 32)
    @Pattern(regexp = "^$|^\\d+(\\.\\d{1,4})?$", message = "entryPrice 형식 오류")
    private String entryPrice;

    @Size(max = 32)
    @Pattern(regexp = "^$|^\\d+(\\.\\d{1,4})?$", message = "triggerPrice 형식 오류")
    private String triggerPrice;

    /** 손절 % (예 -2.0). -50~0 범위 내 음수. */
    @DecimalMin(value = "-50.0", message = "stopLossPercent 최저 -50%")
    @DecimalMax(value = "0.0",   message = "stopLossPercent 는 0 이하여야 합니다")
    private double stopLossPercent;

    @Valid
    private List<TakeProfitLeg> takeProfit;

    @Data
    public static class TakeProfitLeg {
        @DecimalMin(value = "0.1",  message = "익절 percent 는 최소 +0.1%")
        @DecimalMax(value = "500.0", message = "익절 percent 최대 +500%")
        private double percent;

        @DecimalMin(value = "0.01", message = "quantityFraction 최소 0.01")
        @DecimalMax(value = "1.0",  message = "quantityFraction 최대 1.0")
        private double quantityFraction;
    }
}

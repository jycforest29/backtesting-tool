package com.backtesting.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 주문 요청 DTO.
 *
 * 검증 실패 시 전역 핸들러가 ErrorCode.VALIDATION_FAILED (400) + 필드별 상세로 변환.
 * 비즈니스 규칙(예: LIMIT 이면 price 필수) 은 서비스 계층에서 재검증 — DTO 검증은 타입/형식만.
 */
@Data
public class OrderRequest {

    @NotNull(message = "market 은 필수입니다 (KR_STOCK / US_STOCK / JP_STOCK)")
    private AssetType market;

    /** 국내: 6자리 숫자, 해외: 티커 (영문 대문자 및 숫자·`.`). 넉넉히 1~20자. */
    @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,20}$",
             message = "symbol 은 1~20자 영숫자/. 여야 합니다")
    private String symbol;

    /** 해외 전용. 국내 주문이면 null 허용. 허용 값: NAS/NYS/AMS/TSE/HKS/SHS/SZS. */
    @Pattern(regexp = "^(NAS|NYS|AMS|TSE|HKS|SHS|SZS)?$",
             message = "exchange 는 NAS/NYS/AMS/TSE/HKS/SHS/SZS 중 하나여야 합니다")
    private String exchange;

    @Pattern(regexp = "^(?i)(BUY|SELL)$", message = "side 는 BUY 또는 SELL 이어야 합니다")
    private String side;

    @Pattern(regexp = "^(?i)(LIMIT|MARKET)$", message = "orderType 은 LIMIT 또는 MARKET 이어야 합니다")
    private String orderType;

    @Positive(message = "quantity 는 1 이상이어야 합니다")
    private long quantity;

    /**
     * LIMIT 주문의 지정가. 문자열로 유지(JPY 등 소수점/통화 차이). LIMIT 이면 필수.
     * 숫자·소수점만 허용. 1e100 같은 지수 표기 금지.
     */
    @Size(max = 32, message = "price 는 32자 이내여야 합니다")
    @Pattern(regexp = "^$|^\\d+(\\.\\d{1,8})?$",
             message = "price 는 양수 십진수(소수점 8자리 이내) 여야 합니다")
    private String price;
}

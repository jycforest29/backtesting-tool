package com.backtesting.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 실시간 감시종목 추가 요청. market / code / exchange 삼중 키.
 */
@Data
public class WatchlistRequest {

    @NotNull(message = "market 필수 (KR_STOCK / US_STOCK / JP_STOCK)")
    private AssetType market;

    @Size(min = 1, max = 20)
    @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,20}$", message = "code 형식 오류")
    private String code;

    /** 해외만 필수. 국내는 null 허용. */
    @Pattern(regexp = "^(NAS|NYS|AMS|TSE|HKS|SHS|SZS)?$", message = "exchange 값 오류")
    private String exchange;
}

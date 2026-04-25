package com.backtesting.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BacktestRequest {

    @NotNull(message = "assetType 필수 (KR_STOCK / US_STOCK / JP_STOCK / CRYPTO)")
    private AssetType assetType;

    @NotBlank(message = "symbol 필수")
    @Size(max = 20)
    @Pattern(regexp = "^[A-Za-z0-9.\\-^]{1,20}$", message = "symbol 형식이 올바르지 않습니다")
    private String symbol;

    @NotNull(message = "buyDate 필수")
    @PastOrPresent(message = "buyDate 는 오늘 또는 과거여야 합니다")
    private LocalDate buyDate;

    @NotNull
    @DecimalMin(value = "0.00000001", message = "investmentAmount 는 양수여야 합니다")
    private BigDecimal investmentAmount;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$", message = "investmentCurrency 는 3자리 ISO 코드여야 합니다 (예: KRW)")
    private String investmentCurrency;
}

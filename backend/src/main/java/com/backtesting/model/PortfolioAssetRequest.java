package com.backtesting.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PortfolioAssetRequest {

    @NotNull
    private AssetType assetType;

    @NotBlank
    @Size(max = 20)
    @Pattern(regexp = "^[A-Za-z0-9.\\-^]{1,20}$", message = "symbol 형식 오류")
    private String symbol;

    @Size(max = 100)
    private String name;

    /** 0 ~ 100 퍼센트. */
    @NotNull
    @DecimalMin(value = "0.0")  @DecimalMax(value = "100.0")
    private BigDecimal weight;
}

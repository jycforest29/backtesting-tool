package com.backtesting.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class StressTestRequest {

    @NotEmpty(message = "assets 는 1개 이상 필요")
    @Size(max = 50)
    @Valid
    private List<PortfolioAssetRequest> assets;

    @NotNull
    @DecimalMin(value = "0.0", message = "portfolioValue 는 0 이상")
    private BigDecimal portfolioValue;

    @Pattern(regexp = "^[A-Z]{3}$", message = "investmentCurrency 는 3자리 ISO 코드")
    private String investmentCurrency;

    @Valid
    private List<ShockScenario> shocks;

    @Pattern(regexp = "^$|^[A-Z0-9_]{1,40}$", message = "presetScenario 형식 오류")
    private String presetScenario;

    @Data
    public static class ShockScenario {
        @Pattern(regexp = "^[A-Z_0-9]{1,32}$", message = "factor 형식 오류")
        private String factor;

        @NotNull
        @DecimalMin(value = "-100.0") @DecimalMax(value = "500.0")
        private BigDecimal shockPercent;
    }
}

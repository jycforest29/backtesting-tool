package com.backtesting.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class PortfolioBacktestRequest {

    @NotEmpty(message = "assets 는 최소 1개 이상이어야 합니다")
    @Size(max = 50, message = "assets 는 최대 50개입니다")
    @Valid
    private List<PortfolioAssetRequest> assets;

    @NotNull
    @PastOrPresent
    private LocalDate startDate;

    @NotNull
    @DecimalMin(value = "0.00000001", message = "investmentAmount 는 양수여야 합니다")
    private BigDecimal investmentAmount;

    @Pattern(regexp = "^[A-Z]{3}$", message = "investmentCurrency 는 3자리 ISO 코드")
    private String investmentCurrency;

    // Rebalancing
    private RebalancePeriod rebalancePeriod;

    // DCA
    private boolean dcaEnabled;
    @DecimalMin(value = "0.0", message = "dcaMonthlyAmount 는 0 이상")
    private BigDecimal dcaMonthlyAmount;

    // Benchmark comparison
    private boolean benchmarkEnabled;
    @Size(max = 10)
    private List<String> benchmarkSymbols;

    // Tax & Fee
    @Valid
    private TaxFeeConfig taxFeeConfig;
}

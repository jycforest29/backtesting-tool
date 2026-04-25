package com.backtesting.model.quant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class QuantBacktestRequest {

    @NotNull
    private QuantStrategyType strategyType;

    @NotNull
    @PastOrPresent
    private LocalDate startDate;

    @NotNull
    @DecimalMin(value = "1.0", message = "investmentAmount 는 1 이상")
    private BigDecimal investmentAmount;

    @Size(max = 500, message = "customUniverse 최대 500개")
    @Valid
    private List<QuantAsset> customUniverse;

    @Min(1) @Max(100)
    private Integer topN;

    @Min(1) @Max(60)
    private Integer momentumMonths;

    @Min(1) @Max(60)
    private Integer smaMonths;

    private boolean benchmarkEnabled;
}

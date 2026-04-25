package com.backtesting.model.quant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 특정 전략의 자동 매매를 활성화할 때 사용.
 */
@Data
public class QuantEnableRequest {

    @NotNull
    @DecimalMin(value = "1.0", message = "allocatedAmount 는 1 이상")
    private BigDecimal allocatedAmount;

    @Size(max = 500)
    @Valid
    private List<QuantAsset> customUniverse;

    @Min(1) @Max(100)
    private Integer topN;

    @Min(1) @Max(60)
    private Integer momentumMonths;

    @Min(1) @Max(60)
    private Integer smaMonths;
}

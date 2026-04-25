package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TaxFeeResult {
    private boolean enabled;
    private BigDecimal grossProfit;
    private BigDecimal capitalGainsTax;
    private BigDecimal tradingFees;
    private BigDecimal fxFees;
    private BigDecimal totalDeductions;
    private BigDecimal netProfit;
    private BigDecimal netReturnPercent;
    private BigDecimal effectiveTaxRate;
}
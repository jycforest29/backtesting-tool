package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record TaxFeeResult(
        boolean enabled,
        BigDecimal grossProfit,
        BigDecimal capitalGainsTax,
        BigDecimal tradingFees,
        BigDecimal fxFees,
        BigDecimal totalDeductions,
        BigDecimal netProfit,
        BigDecimal netReturnPercent,
        BigDecimal effectiveTaxRate
) {}

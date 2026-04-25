package com.backtesting.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TaxFeeConfig {
    private boolean enabled;

    // Tax rates
    @DecimalMin(value = "0.0") @DecimalMax(value = "100.0")
    private BigDecimal capitalGainsTaxRate;    // e.g., 22 for 22%

    @DecimalMin(value = "0.0")
    private BigDecimal taxExemption;           // e.g., 2500000 (250만원 공제)

    // Fees
    @DecimalMin(value = "0.0") @DecimalMax(value = "10.0")
    private BigDecimal tradingFeeRate;         // e.g., 0.1 for 0.1%

    @DecimalMin(value = "0.0") @DecimalMax(value = "10.0")
    private BigDecimal fxFeeRate;              // e.g., 0.25 for 0.25%

    public static TaxFeeConfig usStock() {
        TaxFeeConfig c = new TaxFeeConfig();
        c.setEnabled(true);
        c.setCapitalGainsTaxRate(new BigDecimal("22"));
        c.setTaxExemption(new BigDecimal("2500000"));
        c.setTradingFeeRate(new BigDecimal("0.1"));
        c.setFxFeeRate(new BigDecimal("0.25"));
        return c;
    }

    public static TaxFeeConfig krStock() {
        TaxFeeConfig c = new TaxFeeConfig();
        c.setEnabled(true);
        c.setCapitalGainsTaxRate(BigDecimal.ZERO); // 소액주주 비과세 (2025 기준)
        c.setTaxExemption(BigDecimal.ZERO);
        c.setTradingFeeRate(new BigDecimal("0.015"));
        c.setFxFeeRate(BigDecimal.ZERO);
        return c;
    }

    public static TaxFeeConfig disabled() {
        TaxFeeConfig c = new TaxFeeConfig();
        c.setEnabled(false);
        return c;
    }
}
package com.backtesting.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BacktestRequest {
    private AssetType assetType;
    private String symbol;
    private LocalDate buyDate;
    private BigDecimal investmentAmount;
    private String investmentCurrency;
}

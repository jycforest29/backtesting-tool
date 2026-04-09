package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class BacktestResult {
    private String symbol;
    private String name;
    private String currency;
    private String investmentCurrency;
    private AssetType assetType;

    private LocalDate buyDate;
    private BigDecimal buyPrice;
    private BigDecimal currentPrice;

    private BigDecimal units;
    private BigDecimal investmentAmount;
    private BigDecimal currentValue;
    private BigDecimal profitLoss;
    private BigDecimal returnPercent;

    private List<PricePoint> priceHistory;
}

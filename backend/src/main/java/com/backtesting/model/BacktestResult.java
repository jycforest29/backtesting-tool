package com.backtesting.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record BacktestResult(
        String symbol,
        String name,
        String currency,
        String investmentCurrency,
        AssetType assetType,
        LocalDate buyDate,
        BigDecimal buyPrice,
        BigDecimal currentPrice,
        BigDecimal units,
        BigDecimal investmentAmount,
        BigDecimal currentValue,
        BigDecimal profitLoss,
        BigDecimal returnPercent,
        List<PricePoint> priceHistory
) {}

package com.backtesting.service;

import com.backtesting.model.BacktestRequest;
import com.backtesting.model.BacktestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final YahooFinanceService yahooFinanceService;

    public BacktestResult calculate(BacktestRequest request) {
        String yahooSymbol = yahooFinanceService.resolveSymbol(request.getAssetType(), request.getSymbol());

        YahooFinanceService.ChartResult chartData;
        try {
            chartData = yahooFinanceService.getChartData(yahooSymbol, request.getBuyDate());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch data for " + yahooSymbol + ": " + e.getMessage(), e);
        }

        if (chartData.priceHistory().isEmpty()) {
            throw new IllegalArgumentException("No price data available for " + yahooSymbol + " from " + request.getBuyDate());
        }

        BigDecimal buyPrice = chartData.priceHistory().get(0).getClose();
        BigDecimal currentPrice = chartData.currentPrice();

        BigDecimal units = request.getInvestmentAmount()
                .divide(buyPrice, 6, RoundingMode.HALF_UP);

        BigDecimal currentValue = units.multiply(currentPrice)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal profitLoss = currentValue.subtract(request.getInvestmentAmount());

        BigDecimal returnPercent = profitLoss
                .divide(request.getInvestmentAmount(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        String investmentCurrency = request.getInvestmentCurrency() != null
                ? request.getInvestmentCurrency()
                : chartData.currency();

        return BacktestResult.builder()
                .symbol(yahooSymbol)
                .name(chartData.name())
                .currency(chartData.currency())
                .investmentCurrency(investmentCurrency)
                .assetType(request.getAssetType())
                .buyDate(request.getBuyDate())
                .buyPrice(buyPrice.setScale(2, RoundingMode.HALF_UP))
                .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP))
                .units(units)
                .investmentAmount(request.getInvestmentAmount())
                .currentValue(currentValue)
                .profitLoss(profitLoss)
                .returnPercent(returnPercent)
                .priceHistory(chartData.priceHistory())
                .build();
    }
}

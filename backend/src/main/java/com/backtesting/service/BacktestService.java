package com.backtesting.service;

import com.backtesting.model.BacktestRequest;
import com.backtesting.model.BacktestResult;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.MarketSymbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final KisMarketDataService marketData;

    public BacktestResult calculate(BacktestRequest request) {
        MarketSymbol sym = MarketSymbol.of(request.getAssetType(), request.getSymbol());
        KisMarketDataService.ChartResult chart;
        try {
            chart = marketData.getChartData(sym, request.getBuyDate());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch data for " + sym.code() + ": " + e.getMessage(), e);
        }
        if (chart.priceHistory().isEmpty()) {
            throw new IllegalArgumentException("No price data for " + sym.code() + " from " + request.getBuyDate());
        }

        BigDecimal buyPrice = chart.priceHistory().get(0).getClose();
        BigDecimal currentPrice = chart.currentPrice();
        BigDecimal units = request.getInvestmentAmount().divide(buyPrice, 6, RoundingMode.HALF_UP);
        BigDecimal currentValue = units.multiply(currentPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal profitLoss = currentValue.subtract(request.getInvestmentAmount());
        BigDecimal returnPercent = profitLoss
                .divide(request.getInvestmentAmount(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        String investCurrency = request.getInvestmentCurrency() != null
                ? request.getInvestmentCurrency() : chart.currency();

        return BacktestResult.builder()
                .symbol(sym.code())
                .name(chart.name())
                .currency(chart.currency())
                .investmentCurrency(investCurrency)
                .assetType(request.getAssetType())
                .buyDate(request.getBuyDate())
                .buyPrice(buyPrice.setScale(2, RoundingMode.HALF_UP))
                .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP))
                .units(units)
                .investmentAmount(request.getInvestmentAmount())
                .currentValue(currentValue)
                .profitLoss(profitLoss)
                .returnPercent(returnPercent)
                .priceHistory(chart.priceHistory())
                .build();
    }
}

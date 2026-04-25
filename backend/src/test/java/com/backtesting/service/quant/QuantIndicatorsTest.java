package com.backtesting.service.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QuantIndicatorsTest {

    @Test
    void closestPriceAtOrBefore_returnsExactDateWhenPresent() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 100, 120);
        LocalDate target = LocalDate.of(2024, 6, 14); // Friday
        BigDecimal v = QuantIndicators.closestPriceAtOrBefore(prices, target);
        assertNotNull(v);
        assertEquals(prices.get(target), v);
    }

    @Test
    void closestPriceAtOrBefore_fallsBackToEarlierWhenTargetIsWeekend() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 100, 120);
        LocalDate weekend = LocalDate.of(2024, 6, 15); // Saturday
        BigDecimal v = QuantIndicators.closestPriceAtOrBefore(prices, weekend);
        assertNotNull(v);
        // 금요일 가격과 같아야 함
        assertEquals(prices.get(LocalDate.of(2024, 6, 14)), v);
    }

    @Test
    void closestPriceAtOrBefore_returnsNullWhenTargetBeforeData() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.flat(
                LocalDate.of(2024, 6, 1), LocalDate.of(2024, 12, 31), 100);
        assertNull(QuantIndicators.closestPriceAtOrBefore(prices, LocalDate.of(2024, 1, 1)));
    }

    @Test
    void momentumReturn_positiveForRisingSeries() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), 100, 150);
        BigDecimal r = QuantIndicators.momentumReturn(prices,
                LocalDate.of(2024, 12, 31), 12);
        assertNotNull(r);
        assertTrue(r.signum() > 0, "12M momentum should be positive for rising series");
    }

    @Test
    void momentumReturn_negativeForFallingSeries() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), 150, 100);
        BigDecimal r = QuantIndicators.momentumReturn(prices,
                LocalDate.of(2024, 12, 31), 12);
        assertNotNull(r);
        assertTrue(r.signum() < 0, "12M momentum should be negative for falling series");
    }

    @Test
    void momentumReturn_nullWhenInsufficientHistory() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.flat(
                LocalDate.of(2024, 10, 1), LocalDate.of(2024, 12, 31), 100);
        BigDecimal r = QuantIndicators.momentumReturn(prices, LocalDate.of(2024, 12, 31), 12);
        assertNull(r, "Should return null when 12M history missing");
    }

    @Test
    void vaaScore_positiveForUptrend() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), 100, 140);
        BigDecimal score = QuantIndicators.vaaScore(prices, LocalDate.of(2024, 12, 31));
        assertNotNull(score);
        assertTrue(score.signum() > 0, "VAA score should be positive on uptrend");
    }

    @Test
    void vaaScore_negativeForDowntrend() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), 150, 100);
        BigDecimal score = QuantIndicators.vaaScore(prices, LocalDate.of(2024, 12, 31));
        assertNotNull(score);
        assertTrue(score.signum() < 0, "VAA score should be negative on downtrend");
    }

    @Test
    void vaaScore_approximatelyZeroForFlat() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.flat(
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), 100);
        BigDecimal score = QuantIndicators.vaaScore(prices, LocalDate.of(2024, 12, 31));
        assertNotNull(score);
        assertEquals(0, score.signum(), "Flat series → VAA score = 0");
    }

    @Test
    void monthlySma_averagesRecentMonths() {
        // 단조 상승 시리즈 → SMA는 중간값 근처
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31), 100, 200);
        BigDecimal sma = QuantIndicators.monthlySma(prices, LocalDate.of(2024, 12, 31), 10);
        assertNotNull(sma);
        // 현재가(200) > 10개월 SMA (최근 10개월 평균은 200보다 작아야 함)
        BigDecimal cur = QuantIndicators.closestPriceAtOrBefore(prices, LocalDate.of(2024, 12, 31));
        assertTrue(cur.compareTo(sma) > 0, "rising series: current > SMA");
    }

    @Test
    void monthlySma_nullWhenInsufficient() {
        Map<LocalDate, BigDecimal> prices = PriceSeriesBuilder.flat(
                LocalDate.of(2024, 10, 1), LocalDate.of(2024, 12, 31), 100);
        assertNull(QuantIndicators.monthlySma(prices, LocalDate.of(2024, 12, 31), 10));
    }
}

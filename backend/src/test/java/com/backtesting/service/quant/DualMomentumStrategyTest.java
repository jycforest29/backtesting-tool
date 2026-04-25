package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DualMomentumStrategyTest {

    private final DualMomentumStrategy strat = new DualMomentumStrategy();
    private final LocalDate asOf = LocalDate.of(2024, 12, 31);

    private List<QuantAsset> universe(String... codes) {
        List<QuantAsset> u = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            QuantAsset.AssetRole role = (i == codes.length - 1)
                    ? QuantAsset.AssetRole.DEFENSIVE
                    : QuantAsset.AssetRole.OFFENSIVE;
            u.add(new QuantAsset(codes[i], codes[i], AssetType.KR_STOCK, role, null));
        }
        return u;
    }

    @Test
    void bestRiskyBeatsSafe_goesFullRisky() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        // RISKY1 상승 +40%, RISKY2 상승 +10%, SAFE 상승 +2%
        prices.put("RISKY1", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 140));
        prices.put("RISKY2", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 110));
        prices.put("SAFE", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 102));

        QuantSignal sig = strat.generateSignal(universe("RISKY1", "RISKY2", "SAFE"),
                prices, asOf, QuantStrategy.StrategyParams.defaults());

        assertEquals(1, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("RISKY1"),
                "상대모멘텀 1위 + 절대모멘텀 통과 → RISKY1 100%");
        assertEquals(0, sig.getTargetWeights().get("RISKY1").compareTo(BigDecimal.ONE));
    }

    @Test
    void riskyUnderperformsSafe_fallsToSafeAsset() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        // RISKY 하락, SAFE 상승 → 절대모멘텀 실패 → SAFE 100%
        prices.put("RISKY1", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 90));
        prices.put("RISKY2", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 95));
        prices.put("SAFE", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 105));

        QuantSignal sig = strat.generateSignal(universe("RISKY1", "RISKY2", "SAFE"),
                prices, asOf, QuantStrategy.StrategyParams.defaults());

        assertTrue(sig.getTargetWeights().containsKey("SAFE"),
                "위험자산 수익률 < 안전자산 → 안전자산 100% (리스크 오프)");
    }

    @Test
    void shouldRebalance_onLastTradingDayOfAnyMonth() {
        LocalDate lastMay = LocalDate.of(2024, 5, 31); // Friday
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 5, 30), lastMay, LocalDate.of(2024, 6, 3));
        assertTrue(strat.shouldRebalance(null, lastMay, dates));

        LocalDate midMonth = LocalDate.of(2024, 5, 15);
        List<LocalDate> dates2 = List.of(LocalDate.of(2024, 5, 14), midMonth,
                LocalDate.of(2024, 5, 16));
        assertFalse(strat.shouldRebalance(null, midMonth, dates2));
    }
}

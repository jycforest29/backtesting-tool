package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArirangFactorRotationStrategyTest {

    private final ArirangFactorRotationStrategy strat = new ArirangFactorRotationStrategy();
    private final LocalDate asOf = LocalDate.of(2024, 12, 31);
    private final LocalDate start = LocalDate.of(2023, 1, 1);

    private List<QuantAsset> universe(String[] offensives, String[] defensives) {
        List<QuantAsset> u = new ArrayList<>();
        for (String o : offensives) u.add(asset(o, QuantAsset.AssetRole.OFFENSIVE));
        for (String d : defensives) u.add(asset(d, QuantAsset.AssetRole.DEFENSIVE));
        return u;
    }

    private QuantAsset asset(String code, QuantAsset.AssetRole role) {
        return new QuantAsset(code, code, AssetType.KR_STOCK, role, null);
    }

    @Test
    void topN_selectsHighestCompositeMomentum_equalWeighted() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("A", PriceSeriesBuilder.linear(start, asOf, 100, 150));
        prices.put("B", PriceSeriesBuilder.linear(start, asOf, 100, 130));
        prices.put("C", PriceSeriesBuilder.linear(start, asOf, 100, 110));
        prices.put("BOND", PriceSeriesBuilder.linear(start, asOf, 100, 101));

        QuantSignal sig = strat.generateSignal(
                universe(new String[]{"A", "B", "C"}, new String[]{"BOND"}),
                prices, asOf,
                new QuantStrategy.StrategyParams(2, null, null));

        assertEquals(2, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("A"));
        assertTrue(sig.getTargetWeights().containsKey("B"));
        assertEquals(0, sig.getTargetWeights().get("A").compareTo(new BigDecimal("0.5")),
                "2슬롯 균등 → 각 50%");
    }

    @Test
    void negativeMomentumSlot_replacedWithDefensive() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("A", PriceSeriesBuilder.linear(start, asOf, 100, 150));
        prices.put("B", PriceSeriesBuilder.linear(start, asOf, 100, 90));
        prices.put("BOND", PriceSeriesBuilder.linear(start, asOf, 100, 103));

        QuantSignal sig = strat.generateSignal(
                universe(new String[]{"A", "B"}, new String[]{"BOND"}),
                prices, asOf,
                new QuantStrategy.StrategyParams(2, null, null));

        assertTrue(sig.getTargetWeights().containsKey("A"));
        assertTrue(sig.getTargetWeights().containsKey("BOND"),
                "두 번째 슬롯은 B 대신 방어자산으로 대체");
    }

    @Test
    void allOffensiveNegative_allSlotsGoDefensive() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("A", PriceSeriesBuilder.linear(start, asOf, 100, 80));
        prices.put("B", PriceSeriesBuilder.linear(start, asOf, 100, 85));
        prices.put("BOND", PriceSeriesBuilder.linear(start, asOf, 100, 103));

        QuantSignal sig = strat.generateSignal(
                universe(new String[]{"A", "B"}, new String[]{"BOND"}),
                prices, asOf,
                new QuantStrategy.StrategyParams(2, null, null));

        assertEquals(1, sig.getTargetWeights().size());
        assertEquals(0, sig.getTargetWeights().get("BOND").compareTo(BigDecimal.ONE),
                "2슬롯 모두 BOND 로 합산 → 100%");
    }

    @Test
    void missingPriceData_noFailure_empty() {
        QuantSignal sig = strat.generateSignal(
                universe(new String[]{"A"}, new String[]{"BOND"}),
                new LinkedHashMap<>(), asOf,
                QuantStrategy.StrategyParams.defaults());
        assertTrue(sig.getTargetWeights().isEmpty());
        assertNotNull(sig.getRationale());
    }

    @Test
    void shouldRebalance_onlyLastTradingDayOfMonth() {
        LocalDate lastDay = LocalDate.of(2024, 5, 31);
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 5, 30), lastDay, LocalDate.of(2024, 6, 3));
        assertTrue(strat.shouldRebalance(null, lastDay, dates));

        LocalDate mid = LocalDate.of(2024, 5, 15);
        assertFalse(strat.shouldRebalance(null, mid,
                List.of(LocalDate.of(2024, 5, 14), mid, LocalDate.of(2024, 5, 16))));
    }
}

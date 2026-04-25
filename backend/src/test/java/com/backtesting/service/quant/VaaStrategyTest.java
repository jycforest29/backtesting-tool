package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VaaStrategyTest {

    private final VaaStrategy strat = new VaaStrategy();
    private final LocalDate asOf = LocalDate.of(2024, 12, 31);

    private QuantAsset off(String code) {
        return new QuantAsset(code, code, AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null);
    }
    private QuantAsset def(String code) {
        return new QuantAsset(code, code, AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null);
    }

    @Test
    void allOffensivePositive_investsInTopOffensive() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("O1", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 140));
        prices.put("O2", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 120));
        prices.put("O3", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 115));
        prices.put("O4", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 110));
        prices.put("D1", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 102));
        prices.put("D2", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 101));
        prices.put("D3", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 103));

        List<QuantAsset> universe = List.of(off("O1"), off("O2"), off("O3"), off("O4"),
                def("D1"), def("D2"), def("D3"));

        QuantSignal sig = strat.generateSignal(universe, prices, asOf,
                QuantStrategy.StrategyParams.defaults());

        assertEquals(1, sig.getTargetWeights().size(), "기본 topN=1");
        assertTrue(sig.getTargetWeights().containsKey("O1"), "최고 모멘텀 O1 선택");
    }

    @Test
    void oneOffensiveNegative_fallsToTopDefensive() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("O1", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 140));
        prices.put("O2", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 120));
        prices.put("O3", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 110));
        prices.put("O4", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 85));  // 음수!
        prices.put("D1", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 105));
        prices.put("D2", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 103));
        prices.put("D3", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 102));

        List<QuantAsset> universe = List.of(off("O1"), off("O2"), off("O3"), off("O4"),
                def("D1"), def("D2"), def("D3"));

        QuantSignal sig = strat.generateSignal(universe, prices, asOf,
                QuantStrategy.StrategyParams.defaults());

        assertEquals(1, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("D1"),
                "공격 하나라도 음수 → 방어 스코어 1위(D1)");
    }

    @Test
    void topNHigherThan1_splitsAmongTopOffensives() {
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("O1", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 140));
        prices.put("O2", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 130));
        prices.put("O3", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 120));
        prices.put("O4", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 110));
        prices.put("D1", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 102));
        prices.put("D2", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 101));
        prices.put("D3", PriceSeriesBuilder.linear(LocalDate.of(2023, 1, 1), asOf, 100, 103));

        List<QuantAsset> universe = List.of(off("O1"), off("O2"), off("O3"), off("O4"),
                def("D1"), def("D2"), def("D3"));

        QuantSignal sig = strat.generateSignal(universe, prices, asOf,
                new QuantStrategy.StrategyParams(2, null, null));

        assertEquals(2, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("O1"));
        assertTrue(sig.getTargetWeights().containsKey("O2"));
        // 각 50%
        assertEquals(new BigDecimal("0.500000"), sig.getTargetWeights().get("O1"));
    }
}

package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DaaStrategyTest {

    private final DaaStrategy strat = new DaaStrategy();
    private final LocalDate asOf = LocalDate.of(2024, 12, 31);

    private QuantAsset canary(String c) {
        return new QuantAsset(c, c, AssetType.KR_STOCK, QuantAsset.AssetRole.CANARY, null);
    }
    private QuantAsset off(String c) {
        return new QuantAsset(c, c, AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null);
    }
    private QuantAsset def(String c) {
        return new QuantAsset(c, c, AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null);
    }

    private Map<String, Map<LocalDate, BigDecimal>> prices(double[] vals, String... codes) {
        Map<String, Map<LocalDate, BigDecimal>> m = new LinkedHashMap<>();
        for (int i = 0; i < codes.length; i++) {
            m.put(codes[i], PriceSeriesBuilder.linear(
                    LocalDate.of(2023, 1, 1), asOf, 100, vals[i]));
        }
        return m;
    }

    @Test
    void bothCanariesPositive_100percentOffensive() {
        var universe = List.of(canary("C1"), canary("C2"),
                off("O1"), off("O2"),
                def("D1"), def("D2"));
        var prices = prices(new double[]{120, 115, 140, 130, 103, 102},
                "C1", "C2", "O1", "O2", "D1", "D2");

        QuantSignal sig = strat.generateSignal(universe, prices, asOf,
                new QuantStrategy.StrategyParams(2, null, null));

        // offShare = 1.0 → 상위 2개 공격자산 균등
        assertEquals(2, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("O1"));
        assertTrue(sig.getTargetWeights().containsKey("O2"));
    }

    @Test
    void oneCanaryNegative_halfOffensiveHalfDefensive() {
        var universe = List.of(canary("C1"), canary("C2"),
                off("O1"), off("O2"),
                def("D1"), def("D2"));
        var prices = prices(new double[]{90, 120, 140, 130, 105, 102},  // C1 음수
                "C1", "C2", "O1", "O2", "D1", "D2");

        QuantSignal sig = strat.generateSignal(universe, prices, asOf,
                new QuantStrategy.StrategyParams(1, null, null));

        // offShare = 0.5
        assertTrue(sig.getTargetWeights().containsKey("O1"));
        assertTrue(sig.getTargetWeights().containsKey("D1"));
        assertEquals(new BigDecimal("0.50"), sig.getTargetWeights().get("O1")
                .setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void bothCanariesNegative_100percentDefensive() {
        var universe = List.of(canary("C1"), canary("C2"),
                off("O1"), off("O2"),
                def("D1"), def("D2"));
        var prices = prices(new double[]{85, 90, 140, 130, 103, 102},
                "C1", "C2", "O1", "O2", "D1", "D2");

        QuantSignal sig = strat.generateSignal(universe, prices, asOf,
                new QuantStrategy.StrategyParams(1, null, null));

        // 방어자산 스코어 1위 (D1: +3 vs D2: +2)
        assertTrue(sig.getTargetWeights().containsKey("D1"));
        assertFalse(sig.getTargetWeights().containsKey("O1"),
                "모든 카나리아 음수 → 공격 0%");
    }
}

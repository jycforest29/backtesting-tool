package com.backtesting.service.quant;

import com.backtesting.model.quant.QuantSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HalloweenStrategyTest {

    private final HalloweenStrategy strat = new HalloweenStrategy();

    @Test
    void december_goesToWinterAsset_100percent() {
        QuantSignal sig = strat.generateSignal(HalloweenStrategy.DEFAULT_UNIVERSE,
                Map.of(), LocalDate.of(2024, 12, 15),
                QuantStrategy.StrategyParams.defaults());

        assertEquals(1, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("069500"));
        assertEquals(0, sig.getTargetWeights().get("069500").compareTo(BigDecimal.ONE));
    }

    @Test
    void january_isWinter() {
        QuantSignal sig = strat.generateSignal(HalloweenStrategy.DEFAULT_UNIVERSE,
                Map.of(), LocalDate.of(2024, 1, 15),
                QuantStrategy.StrategyParams.defaults());
        assertTrue(sig.getTargetWeights().containsKey("069500"));
    }

    @Test
    void april_stillWinter_boundary() {
        QuantSignal sig = strat.generateSignal(HalloweenStrategy.DEFAULT_UNIVERSE,
                Map.of(), LocalDate.of(2024, 4, 30),
                QuantStrategy.StrategyParams.defaults());
        assertTrue(sig.getTargetWeights().containsKey("069500"), "4월 말까지는 겨울");
    }

    @Test
    void may_switchesToSummerBondAsset() {
        QuantSignal sig = strat.generateSignal(HalloweenStrategy.DEFAULT_UNIVERSE,
                Map.of(), LocalDate.of(2024, 5, 15),
                QuantStrategy.StrategyParams.defaults());
        assertEquals(1, sig.getTargetWeights().size());
        assertTrue(sig.getTargetWeights().containsKey("153130"));
    }

    @Test
    void october_stillSummer_boundary() {
        QuantSignal sig = strat.generateSignal(HalloweenStrategy.DEFAULT_UNIVERSE,
                Map.of(), LocalDate.of(2024, 10, 31),
                QuantStrategy.StrategyParams.defaults());
        assertTrue(sig.getTargetWeights().containsKey("153130"), "10월 말까지는 여름");
    }

    @Test
    void november_backToWinter() {
        QuantSignal sig = strat.generateSignal(HalloweenStrategy.DEFAULT_UNIVERSE,
                Map.of(), LocalDate.of(2024, 11, 1),
                QuantStrategy.StrategyParams.defaults());
        assertTrue(sig.getTargetWeights().containsKey("069500"));
    }

    @Test
    void shouldRebalance_onlyInAprOct_lastTradingDay() {
        // 2024-04-30 is Tuesday (month-end trading day)
        LocalDate apr30 = LocalDate.of(2024, 4, 30);
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 4, 29),
                apr30,
                LocalDate.of(2024, 5, 2) // next month
        );
        assertTrue(strat.shouldRebalance(null, apr30, dates));
    }

    @Test
    void shouldRebalance_falseForNonSwitchingMonths() {
        LocalDate jun28 = LocalDate.of(2024, 6, 28);
        List<LocalDate> dates = List.of(jun28, LocalDate.of(2024, 7, 1));
        assertFalse(strat.shouldRebalance(null, jun28, dates));
    }
}

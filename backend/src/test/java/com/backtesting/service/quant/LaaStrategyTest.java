package com.backtesting.service.quant;

import com.backtesting.model.quant.QuantSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LaaStrategyTest {

    private final LaaStrategy strat = new LaaStrategy();
    private final LocalDate asOf = LocalDate.of(2024, 12, 31);

    private Map<String, Map<LocalDate, BigDecimal>> makePrices(double spyEndPrice) {
        Map<String, Map<LocalDate, BigDecimal>> m = new LinkedHashMap<>();
        // 정적 3종
        m.put("360750", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, spyEndPrice));
        m.put("132030", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 110));
        m.put("136340", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 105));
        // 가변 2종
        m.put("133690", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 150));
        m.put("153130", PriceSeriesBuilder.linear(
                LocalDate.of(2023, 1, 1), asOf, 100, 102));
        return m;
    }

    @Test
    void staticPortionAlways75percent_3x25() {
        QuantSignal sig = strat.generateSignal(LaaStrategy.DEFAULT_UNIVERSE,
                makePrices(140), asOf, QuantStrategy.StrategyParams.defaults());

        // 정적 3자산 각 25%
        assertEquals(new BigDecimal("0.25"), sig.getTargetWeights().get("360750")
                .setScale(2, java.math.RoundingMode.HALF_UP));
        assertEquals(new BigDecimal("0.25"), sig.getTargetWeights().get("132030")
                .setScale(2, java.math.RoundingMode.HALF_UP));
        assertEquals(new BigDecimal("0.25"), sig.getTargetWeights().get("136340")
                .setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void trendUp_variableGoesToQQQ() {
        // SPY 상승 → 추세 양호 → 가변 25%를 QQQ(133690)에 배분
        QuantSignal sig = strat.generateSignal(LaaStrategy.DEFAULT_UNIVERSE,
                makePrices(150), asOf, QuantStrategy.StrategyParams.defaults());

        assertTrue(sig.getTargetWeights().containsKey("133690"), "추세↑ → QQQ 보유");
        assertFalse(sig.getTargetWeights().containsKey("153130"),
                "추세↑ → 단기채 미보유");
    }

    @Test
    void trendDown_variableGoesToShortBond() {
        // SPY 하락 → 추세 하락 → 가변 25%를 단기채(153130)로
        QuantSignal sig = strat.generateSignal(LaaStrategy.DEFAULT_UNIVERSE,
                makePrices(80), asOf, QuantStrategy.StrategyParams.defaults());

        assertTrue(sig.getTargetWeights().containsKey("153130"), "추세↓ → 단기채 방어");
    }
}

package com.backtesting.service.quant;

import com.backtesting.model.quant.FundamentalData;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 팩터 전략 대표 테스트. selectTopN 로직 검증.
 * FundamentalDataService는 Mockito로 스텁.
 */
class MagicFormulaStrategyTest {

    private FundamentalData mk(String code, double roa, double per) {
        FundamentalData f = FundamentalData.builder()
                .stockCode(code)
                .name(code)
                .roa(BigDecimal.valueOf(roa))
                .per(BigDecimal.valueOf(per))
                .build();
        return f;
    }

    @Test
    void magicFormula_picksHighRoaLowPer() {
        FundamentalDataService fundamentals = Mockito.mock(FundamentalDataService.class);
        KospiUniverseService universe = Mockito.mock(KospiUniverseService.class);

        when(fundamentals.getAll(anyList())).thenReturn(List.of(
                mk("A", 15.0, 8.0),   // 최고 ROA + 저PER — 1위
                mk("B", 12.0, 10.0),  // 2위
                mk("C", 5.0, 15.0),   // 낮은 ROA + 고PER — 하위
                mk("D", 10.0, 12.0)
        ));
        when(universe.all()).thenReturn(List.of());

        MagicFormulaStrategy strat = new MagicFormulaStrategy(fundamentals, universe);
        QuantSignal sig = strat.generateSignal(
                strat.defaultUniverse(), java.util.Map.of(),
                java.time.LocalDate.now(),
                new QuantStrategy.StrategyParams(2, null, null));

        assertEquals(2, sig.getTargetWeights().size(), "topN=2");
        assertTrue(sig.getTargetWeights().containsKey("A"), "ROA 1위 + PER 1위 = 합산 최저 → A");
        assertTrue(sig.getTargetWeights().containsKey("B"));

        // 동일가중
        BigDecimal wA = sig.getTargetWeights().get("A");
        BigDecimal wB = sig.getTargetWeights().get("B");
        assertEquals(wA, wB);
    }

    @Test
    void magicFormula_excludesNegativePer() {
        FundamentalDataService fundamentals = Mockito.mock(FundamentalDataService.class);
        KospiUniverseService universe = Mockito.mock(KospiUniverseService.class);

        when(fundamentals.getAll(anyList())).thenReturn(List.of(
                mk("A", -5.0, 8.0),   // ROA 음수 (적자) — 밸류 순위는 유효하나 rank에서 제외됨
                mk("B", 10.0, -5.0),  // PER 음수 — 적자 → rank 제외
                mk("C", 8.0, 12.0),   // 정상
                mk("D", 6.0, 10.0)    // 정상
        ));
        when(universe.all()).thenReturn(List.of());

        MagicFormulaStrategy strat = new MagicFormulaStrategy(fundamentals, universe);
        QuantSignal sig = strat.generateSignal(
                strat.defaultUniverse(), java.util.Map.of(),
                java.time.LocalDate.now(),
                new QuantStrategy.StrategyParams(2, null, null));

        // A(ROA<0), B(PER<0)는 순위에서 제외되어 실질 후보는 C, D만
        assertTrue(sig.getTargetWeights().containsKey("C"));
        assertTrue(sig.getTargetWeights().containsKey("D"));
        assertFalse(sig.getTargetWeights().containsKey("A"));
        assertFalse(sig.getTargetWeights().containsKey("B"));
    }

    @Test
    void rebalanceTiming_onlyQuarterEnd() {
        FundamentalDataService fundamentals = Mockito.mock(FundamentalDataService.class);
        KospiUniverseService universe = Mockito.mock(KospiUniverseService.class);
        MagicFormulaStrategy strat = new MagicFormulaStrategy(fundamentals, universe);

        // 3월 말
        assertTrue(strat.shouldRebalance(null,
                java.time.LocalDate.of(2024, 3, 29),
                List.of(java.time.LocalDate.of(2024, 3, 28),
                        java.time.LocalDate.of(2024, 3, 29),
                        java.time.LocalDate.of(2024, 4, 1))));

        // 1월 말 → false (팩터 전략은 분기만)
        assertFalse(strat.shouldRebalance(null,
                java.time.LocalDate.of(2024, 1, 31),
                List.of(java.time.LocalDate.of(2024, 1, 30),
                        java.time.LocalDate.of(2024, 1, 31),
                        java.time.LocalDate.of(2024, 2, 1))));
    }
}

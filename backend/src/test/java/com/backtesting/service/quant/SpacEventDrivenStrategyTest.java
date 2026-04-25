package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.service.dart.SpacEventService;
import com.backtesting.service.dart.SpacUniverseService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpacEventDrivenStrategyTest {

    private final SpacUniverseService universe = mock(SpacUniverseService.class);
    private final SpacEventService events = mock(SpacEventService.class);
    private final SpacEventDrivenStrategy strat = new SpacEventDrivenStrategy(universe, events);

    private final LocalDate asOf = LocalDate.of(2025, 6, 15);

    private QuantAsset asset(String code) {
        return new QuantAsset(code, code + "스팩", AssetType.KR_STOCK,
                QuantAsset.AssetRole.OFFENSIVE, null);
    }

    private Map<LocalDate, BigDecimal> flat(double price) {
        Map<LocalDate, BigDecimal> m = new TreeMap<>();
        for (LocalDate d = asOf.minusDays(30); !d.isAfter(asOf); d = d.plusDays(1)) {
            m.put(d, BigDecimal.valueOf(price));
        }
        return m;
    }

    @Test
    void inBand_noMerger_selected() {
        when(universe.find("001")).thenReturn(
                new SpacUniverseService.SpacEntry("001", "CORP001", "X스팩"));
        when(events.latestAnnouncementBefore(eq("001"), eq("CORP001"), any()))
                .thenReturn(Optional.empty());

        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("001", flat(2020));

        QuantSignal sig = strat.generateSignal(List.of(asset("001")), prices, asOf,
                QuantStrategy.StrategyParams.defaults());

        assertEquals(1, sig.getTargetWeights().size());
        assertEquals(0, sig.getTargetWeights().get("001").compareTo(BigDecimal.ONE));
    }

    @Test
    void outOfBand_notSelected() {
        when(universe.find("002")).thenReturn(
                new SpacUniverseService.SpacEntry("002", "CORP002", "Y스팩"));
        when(events.latestAnnouncementBefore(any(), any(), any())).thenReturn(Optional.empty());

        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("002", flat(2500));

        QuantSignal sig = strat.generateSignal(List.of(asset("002")), prices, asOf,
                QuantStrategy.StrategyParams.defaults());

        assertTrue(sig.getTargetWeights().isEmpty());
        assertTrue(sig.getRationale().contains("현금"));
    }

    @Test
    void recentMerger_disqualifies() {
        when(universe.find("003")).thenReturn(
                new SpacUniverseService.SpacEntry("003", "CORP003", "Z스팩"));
        when(events.latestAnnouncementBefore(eq("003"), eq("CORP003"), any()))
                .thenReturn(Optional.of(asOf.minusDays(3)));

        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("003", flat(2020));

        QuantSignal sig = strat.generateSignal(List.of(asset("003")), prices, asOf,
                QuantStrategy.StrategyParams.defaults());

        assertTrue(sig.getTargetWeights().isEmpty(),
                "최근 5일 내 합병 공시 → 청산 (weight 0)");
    }

    @Test
    void oldMerger_beforeLookback_doesNotDisqualify() {
        when(universe.find("004")).thenReturn(
                new SpacUniverseService.SpacEntry("004", "CORP004", "W스팩"));
        when(events.latestAnnouncementBefore(eq("004"), eq("CORP004"), any()))
                .thenReturn(Optional.of(asOf.minusDays(30)));

        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        prices.put("004", flat(2010));

        QuantSignal sig = strat.generateSignal(List.of(asset("004")), prices, asOf,
                QuantStrategy.StrategyParams.defaults());

        assertTrue(sig.getTargetWeights().containsKey("004"));
    }

    @Test
    void missingPriceData_skippedSilently() {
        QuantSignal sig = strat.generateSignal(List.of(asset("005")),
                new LinkedHashMap<>(), asOf, QuantStrategy.StrategyParams.defaults());
        assertTrue(sig.getTargetWeights().isEmpty());
        assertNotNull(sig.getRationale());
    }

    @Test
    void dailyRebalance_alwaysTrue() {
        LocalDate d = LocalDate.of(2025, 6, 15);
        assertTrue(strat.shouldRebalance(null, d, List.of(d)));
    }

    @Test
    void dynamicUniverseFlag_true() {
        assertTrue(strat.dynamicUniverse(),
                "SPAC 유니버스는 희소·동적 — 백테스트 엔진이 tolerant 모드로 동작");
    }
}

package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Vigilant Asset Allocation (Keller, 2017) - Aggressive 변형.
 * 공격자산 4, 방어자산 3.
 * 모든 공격자산의 13612W 스코어가 양수이면 → 공격자산 중 탑-1(또는 탑-N) 100%
 * 하나라도 음수이면 → 방어자산 중 스코어 최고 1개 100%
 *
 * 강환국이 『거인의 포트폴리오』에서 소개한 핵심 동적 자산배분 전략.
 */
@Component
public class VaaStrategy implements QuantStrategy {

    static final List<QuantAsset> DEFAULT_UNIVERSE = List.of(
            // 공격자산 (VAA Aggressive: SPY, EFA, EEM, AGG → KR ETF 대응)
            new QuantAsset("360750", "TIGER 미국S&P500", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("195930", "TIGER 선진국MSCI World", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("195980", "ARIRANG 신흥국MSCI", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("136340", "TIGER 미국채10년선물", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            // 방어자산 (LQD, IEF, SHY → 국내 단기/중기 채권 ETF)
            new QuantAsset("153130", "KODEX 단기채권", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null),
            new QuantAsset("114260", "KODEX 국고채3년", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null),
            new QuantAsset("130730", "KOSEF 단기자금", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null)
    );

    @Override
    public QuantStrategyType type() { return QuantStrategyType.VAA; }

    @Override
    public String displayName() { return "VAA (Vigilant AA)"; }

    @Override
    public String description() {
        return "공격 4자산의 13612W 모멘텀 스코어가 모두 양수이면 공격자산 중 스코어 최고에 100%. "
                + "하나라도 음수이면 방어자산 중 스코어 최고에 100%. 월말 리밸런싱.";
    }

    @Override
    public List<QuantAsset> defaultUniverse() { return DEFAULT_UNIVERSE; }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int topN = params.topN(1);
        List<QuantAsset> off = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.OFFENSIVE).toList();
        List<QuantAsset> def = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.DEFENSIVE).toList();

        List<QuantSignal.ScoreRow> diag = new ArrayList<>();
        Map<String, BigDecimal> offScores = new LinkedHashMap<>();
        int offWithData = 0;
        boolean anyNegative = false;
        for (QuantAsset a : off) {
            BigDecimal s = QuantIndicators.vaaScore(prices.get(a.getSymbol()), asOfDate);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label("공격 " + a.getName() + " 13612W")
                    .score(s).selected(false).build());
            if (s != null) {
                offScores.put(a.getSymbol(), s);
                offWithData++;
                if (s.signum() < 0) anyNegative = true;
            }
        }

        Map<String, BigDecimal> defScores = new LinkedHashMap<>();
        for (QuantAsset a : def) {
            BigDecimal s = QuantIndicators.vaaScore(prices.get(a.getSymbol()), asOfDate);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label("방어 " + a.getName() + " 13612W")
                    .score(s).selected(false).build());
            if (s != null) defScores.put(a.getSymbol(), s);
        }

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        String rationale;

        if (offWithData == off.size() && !anyNegative && !offScores.isEmpty()) {
            List<Map.Entry<String, BigDecimal>> sorted = offScores.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue())).toList();
            int n = Math.min(topN, sorted.size());
            BigDecimal w = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
            for (int i = 0; i < n; i++) {
                weights.put(sorted.get(i).getKey(), w);
                markSelected(diag, sorted.get(i).getKey());
            }
            rationale = String.format("공격자산 모두 양수 모멘텀 → 상위 %d개 공격자산에 균등 100%%", n);
        } else if (!defScores.isEmpty()) {
            Map.Entry<String, BigDecimal> best = defScores.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow();
            weights.put(best.getKey(), BigDecimal.ONE);
            markSelected(diag, best.getKey());
            rationale = "공격자산 중 음수 모멘텀 존재 → 방어자산 스코어 1위에 100% (리스크 오프)";
        } else {
            rationale = "데이터 부족 → 현금";
        }

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(rationale)
                .diagnostics(diag)
                .build();
    }

    private static void markSelected(List<QuantSignal.ScoreRow> diag, String symbol) {
        for (int i = 0; i < diag.size(); i++) {
            if (diag.get(i).getSymbol().equals(symbol)) {
                QuantSignal.ScoreRow r = diag.get(i);
                diag.set(i, QuantSignal.ScoreRow.builder()
                        .symbol(r.getSymbol()).label(r.getLabel())
                        .score(r.getScore()).selected(true).build());
            }
        }
    }

    @Override
    public boolean shouldRebalance(LocalDate prev, LocalDate today, List<LocalDate> availableDates) {
        return RebalanceTiming.isLastTradingDayOfMonth(today, availableDates);
    }
}

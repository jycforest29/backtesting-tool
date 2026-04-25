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
 * Defensive Asset Allocation (Keller & Keuning, 2018).
 * - 카나리아 자산(EEM, BND) 13612W 스코어로 시장 위험 감지
 * - 카나리아 둘 다 양수: 공격자산 탑-N 균등 (기본 탑-6)
 * - 카나리아 하나 음수: 50% 공격 + 50% 방어
 * - 카나리아 둘 다 음수: 방어자산 스코어 1위 100%
 *
 * 강환국의 『거인의 포트폴리오』에서 VAA와 함께 소개.
 */
@Component
public class DaaStrategy implements QuantStrategy {

    static final List<QuantAsset> DEFAULT_UNIVERSE = List.of(
            // 카나리아
            new QuantAsset("195980", "ARIRANG 신흥국MSCI (Canary)", AssetType.KR_STOCK, QuantAsset.AssetRole.CANARY, null),
            new QuantAsset("136340", "TIGER 미국채10년 (Canary)", AssetType.KR_STOCK, QuantAsset.AssetRole.CANARY, null),
            // 공격자산 12종 (DAA 원본). KR ETF로 근사:
            new QuantAsset("360750", "TIGER 미국S&P500", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("133690", "TIGER 미국나스닥100", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("195930", "TIGER 선진국MSCI World", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("238720", "KINDEX 일본Nikkei225", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("069500", "KODEX 200", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("132030", "KODEX 골드선물", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("261220", "KODEX WTI원유선물", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("157450", "TIGER 미국MSCI리츠", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("308620", "KODEX 미국채10년선물", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            // 방어자산
            new QuantAsset("153130", "KODEX 단기채권", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null),
            new QuantAsset("114260", "KODEX 국고채3년", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null)
    );

    @Override
    public QuantStrategyType type() { return QuantStrategyType.DAA; }

    @Override
    public String displayName() { return "DAA (Defensive AA)"; }

    @Override
    public String description() {
        return "카나리아 자산(신흥국·미국채)의 모멘텀으로 시장 위험을 감지. "
                + "둘 다 양수면 공격자산 탑-N(기본 6) 균등 투자, "
                + "하나 음수면 공격50%+방어50%, 둘 다 음수면 방어자산 100%.";
    }

    @Override
    public List<QuantAsset> defaultUniverse() { return DEFAULT_UNIVERSE; }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int topN = params.topN(6);
        List<QuantAsset> canary = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.CANARY).toList();
        List<QuantAsset> off = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.OFFENSIVE).toList();
        List<QuantAsset> def = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.DEFENSIVE).toList();

        List<QuantSignal.ScoreRow> diag = new ArrayList<>();

        int canaryPositive = 0;
        for (QuantAsset a : canary) {
            BigDecimal s = QuantIndicators.vaaScore(prices.get(a.getSymbol()), asOfDate);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label("카나리아 " + a.getName())
                    .score(s).selected(false).build());
            if (s != null && s.signum() > 0) canaryPositive++;
        }

        Map<String, BigDecimal> offScores = scoreAssets(off, prices, asOfDate, diag, "공격");
        Map<String, BigDecimal> defScores = scoreAssets(def, prices, asOfDate, diag, "방어");

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        String rationale;

        double offShare;
        if (canaryPositive == canary.size()) {
            offShare = 1.0; rationale = "카나리아 모두 양수 → 공격 100% (risk on)";
        } else if (canaryPositive == 0) {
            offShare = 0.0; rationale = "카나리아 모두 음수 → 방어 100% (risk off)";
        } else {
            offShare = 0.5; rationale = "카나리아 혼조 → 공격 50% + 방어 50%";
        }

        if (offShare > 0 && !offScores.isEmpty()) {
            List<Map.Entry<String, BigDecimal>> sortedOff = offScores.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue())).toList();
            int n = Math.min(topN, sortedOff.size());
            BigDecimal w = BigDecimal.valueOf(offShare)
                    .divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
            for (int i = 0; i < n; i++) {
                weights.put(sortedOff.get(i).getKey(), w);
                markSelected(diag, sortedOff.get(i).getKey());
            }
        }
        if (offShare < 1 && !defScores.isEmpty()) {
            Map.Entry<String, BigDecimal> bestDef = defScores.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow();
            BigDecimal defWeight = BigDecimal.valueOf(1 - offShare);
            weights.merge(bestDef.getKey(), defWeight, BigDecimal::add);
            markSelected(diag, bestDef.getKey());
        }

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(rationale)
                .diagnostics(diag)
                .build();
    }

    private static Map<String, BigDecimal> scoreAssets(List<QuantAsset> list,
                                                       Map<String, Map<LocalDate, BigDecimal>> prices,
                                                       LocalDate asOf,
                                                       List<QuantSignal.ScoreRow> diag,
                                                       String prefix) {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        for (QuantAsset a : list) {
            BigDecimal s = QuantIndicators.vaaScore(prices.get(a.getSymbol()), asOf);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label(prefix + " " + a.getName())
                    .score(s).selected(false).build());
            if (s != null) scores.put(a.getSymbol(), s);
        }
        return scores;
    }

    private static void markSelected(List<QuantSignal.ScoreRow> diag, String symbol) {
        for (int i = 0; i < diag.size(); i++) {
            QuantSignal.ScoreRow r = diag.get(i);
            if (r.getSymbol().equals(symbol) && !r.isSelected()) {
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

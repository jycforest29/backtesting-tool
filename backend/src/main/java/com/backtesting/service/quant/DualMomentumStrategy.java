package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Gary Antonacci 듀얼 모멘텀.
 * 1. 상대 모멘텀: 위험자산 후보 중 12개월 수익률 최고인 것 선택
 * 2. 절대 모멘텀: 그 위험자산의 12개월 수익률이 안전자산보다 높을 때만 그 자산에 투자,
 *    아니면 안전자산(채권) 100%
 *
 * 강환국이 여러 책에서 한국 시장에서도 적용 가능하다고 소개한 대표 전략.
 */
@Component
public class DualMomentumStrategy implements QuantStrategy {

    /** role: OFFENSIVE = 위험자산 후보, DEFENSIVE = 안전자산(절대 모멘텀 벤치마크 + 회피처). */
    static final List<QuantAsset> DEFAULT_UNIVERSE = List.of(
            new QuantAsset("360750", "TIGER 미국S&P500", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("069500", "KODEX 200", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("195930", "TIGER 선진국MSCI World", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("153130", "KODEX 단기채권", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null)
    );

    @Override
    public QuantStrategyType type() { return QuantStrategyType.DUAL_MOMENTUM; }

    @Override
    public String displayName() { return "듀얼 모멘텀"; }

    @Override
    public String description() {
        return "위험자산 중 12개월 수익률 최고 종목을 선택(상대 모멘텀). "
                + "그 수익률이 안전자산보다 낮으면 안전자산 100%(절대 모멘텀). "
                + "월말 리밸런싱.";
    }

    @Override
    public List<QuantAsset> defaultUniverse() { return DEFAULT_UNIVERSE; }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int lookback = params.momentumMonths(12);
        List<QuantAsset> offensive = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.OFFENSIVE).toList();
        QuantAsset defensive = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.DEFENSIVE)
                .findFirst().orElse(null);

        List<QuantSignal.ScoreRow> diag = new ArrayList<>();

        // 1) 상대 모멘텀: 위험자산 중 최고 수익률
        QuantAsset bestRisky = null;
        BigDecimal bestReturn = null;
        for (QuantAsset a : offensive) {
            BigDecimal r = QuantIndicators.momentumReturn(prices.get(a.getSymbol()), asOfDate, lookback);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label(a.getName() + " " + lookback + "M Return")
                    .score(r).selected(false).build());
            if (r == null) continue;
            if (bestReturn == null || r.compareTo(bestReturn) > 0) {
                bestReturn = r;
                bestRisky = a;
            }
        }

        // 2) 절대 모멘텀: 위험자산 수익률 > 안전자산 수익률
        BigDecimal defReturn = defensive != null
                ? QuantIndicators.momentumReturn(prices.get(defensive.getSymbol()), asOfDate, lookback)
                : BigDecimal.ZERO;
        if (defensive != null) {
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(defensive.getSymbol()).label(defensive.getName() + " " + lookback + "M Return (벤치)")
                    .score(defReturn).selected(false).build());
        }

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        String rationale;
        if (bestRisky != null && bestReturn != null
                && (defReturn == null || bestReturn.compareTo(defReturn) > 0)) {
            weights.put(bestRisky.getSymbol(), BigDecimal.ONE);
            markSelected(diag, bestRisky.getSymbol());
            rationale = String.format("상대모멘텀 1위=%s(%s%%). 절대모멘텀 통과 → 100%% 투자",
                    bestRisky.getName(), pct(bestReturn));
        } else if (defensive != null) {
            weights.put(defensive.getSymbol(), BigDecimal.ONE);
            markSelected(diag, defensive.getSymbol());
            rationale = "위험자산 모멘텀 < 안전자산 → 안전자산 100% (리스크 오프)";
        } else {
            rationale = "데이터 부족 또는 위험자산 선택 실패 → 현금";
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

    private static String pct(BigDecimal v) {
        if (v == null) return "-";
        return v.multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    @Override
    public boolean shouldRebalance(LocalDate prev, LocalDate today, List<LocalDate> availableDates) {
        return RebalanceTiming.isLastTradingDayOfMonth(today, availableDates);
    }
}

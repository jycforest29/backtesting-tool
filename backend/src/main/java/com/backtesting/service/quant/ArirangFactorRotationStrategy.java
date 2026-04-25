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
 * ARIRANG 팩터 로테이션.
 * 한화자산운용 ARIRANG ETF 유니버스에서 3/6/12M 총수익률 등가중 복합 모멘텀 스코어로
 * 상위 N개(기본 2)를 선택. 각 슬롯의 스코어가 음수이면 해당 슬롯을 방어자산
 * (ARIRANG 단기/종합채권)으로 대체 (절대 모멘텀 필터). 월말 리밸런싱.
 *
 * 한화투자증권 계좌에서 그대로 실매매 가능한 계층 A 전략.
 */
@Component
public class ArirangFactorRotationStrategy implements QuantStrategy {

    static final List<QuantAsset> DEFAULT_UNIVERSE = List.of(
            // 공격자산: ARIRANG 팩터/테마 ETF (배당·저변동·K방산·해외 지수)
            new QuantAsset("161510", "ARIRANG 고배당주", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("251590", "ARIRANG 고배당저변동50", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("449450", "ARIRANG K방산Fn", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("269540", "ARIRANG 미국S&P500(H)", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("195980", "ARIRANG 신흥국MSCI", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            // 방어자산: ARIRANG 채권 ETF
            new QuantAsset("227830", "ARIRANG 단기채권액티브", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null),
            new QuantAsset("332610", "ARIRANG 종합채권(AA-이상)액티브", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null)
    );

    @Override
    public QuantStrategyType type() { return QuantStrategyType.ARIRANG_FACTOR_ROTATION; }

    @Override
    public String displayName() { return "ARIRANG 팩터 로테이션"; }

    @Override
    public String description() {
        return "한화자산운용 ARIRANG ETF 유니버스에서 3/6/12M 등가중 복합 모멘텀 상위 N개 선택. "
                + "스코어 음수 슬롯은 ARIRANG 채권 ETF로 대체 (절대 모멘텀 필터). 월말 리밸런싱.";
    }

    @Override
    public List<QuantAsset> defaultUniverse() { return DEFAULT_UNIVERSE; }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int topN = params.topN(2);

        List<QuantAsset> off = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.OFFENSIVE).toList();
        List<QuantAsset> def = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.DEFENSIVE).toList();

        List<QuantSignal.ScoreRow> diag = new ArrayList<>();

        Map<String, BigDecimal> offScores = new LinkedHashMap<>();
        for (QuantAsset a : off) {
            BigDecimal s = compositeMomentum(prices.get(a.getSymbol()), asOfDate);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label("공격 " + a.getName() + " 3/6/12M 복합")
                    .score(s).selected(false).build());
            if (s != null) offScores.put(a.getSymbol(), s);
        }

        Map<String, BigDecimal> defScores = new LinkedHashMap<>();
        for (QuantAsset a : def) {
            BigDecimal s = compositeMomentum(prices.get(a.getSymbol()), asOfDate);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol()).label("방어 " + a.getName() + " 3/6/12M 복합")
                    .score(s).selected(false).build());
            if (s != null) defScores.put(a.getSymbol(), s);
        }

        List<Map.Entry<String, BigDecimal>> rankedOff = offScores.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue())).toList();

        String bestDefensive = defScores.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        BigDecimal slotWeight = BigDecimal.ONE.divide(BigDecimal.valueOf(topN), 6, RoundingMode.HALF_UP);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        int filledOff = 0;
        int filledDef = 0;

        for (int i = 0; i < topN; i++) {
            if (i < rankedOff.size() && rankedOff.get(i).getValue().signum() > 0) {
                String sym = rankedOff.get(i).getKey();
                weights.merge(sym, slotWeight, BigDecimal::add);
                markSelected(diag, sym);
                filledOff++;
            } else if (bestDefensive != null) {
                weights.merge(bestDefensive, slotWeight, BigDecimal::add);
                markSelected(diag, bestDefensive);
                filledDef++;
            }
        }

        String rationale;
        if (filledOff == topN) {
            rationale = String.format("ARIRANG 공격자산 상위 %d개 모두 양(+) 모멘텀 → 균등 투자", topN);
        } else if (filledOff == 0 && filledDef > 0) {
            rationale = String.format("공격자산 양(+) 모멘텀 없음 → ARIRANG 방어자산 %d슬롯 (리스크 오프)", filledDef);
        } else if (filledOff > 0) {
            rationale = String.format("공격자산 %d슬롯 + 방어자산 %d슬롯 (일부 음(-) 모멘텀 대체)",
                    filledOff, filledDef);
        } else {
            rationale = "공격/방어 모두 데이터 부족 → 현금 100%";
        }

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(rationale)
                .diagnostics(diag)
                .build();
    }

    private static BigDecimal compositeMomentum(Map<LocalDate, BigDecimal> prices, LocalDate asOf) {
        BigDecimal r3 = QuantIndicators.momentumReturn(prices, asOf, 3);
        BigDecimal r6 = QuantIndicators.momentumReturn(prices, asOf, 6);
        BigDecimal r12 = QuantIndicators.momentumReturn(prices, asOf, 12);
        if (r3 == null || r6 == null || r12 == null) return null;
        return r3.add(r6).add(r12).divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP);
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

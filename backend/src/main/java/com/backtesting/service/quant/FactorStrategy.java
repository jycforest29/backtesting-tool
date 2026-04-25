package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.FundamentalData;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 계층 B(팩터 투자) 전략의 공통 베이스.
 * - 가격 히스토리가 아닌 재무제표 기반으로 종목을 선정
 * - 자산배분 전략과 달리 defaultUniverse()가 KOSPI 유니버스 전체 (스크리닝 대상)
 * - generateSignal은 FundamentalDataService로 순위 매긴 후 상위 N 동일가중
 *
 * 백테스트 주의:
 *   - 현재 재무제표를 사용하여 스크리닝하므로 생존편향이 있다.
 *   - 과거 특정 시점에 상장 안 된 종목이 포함될 수 있음.
 *   - UI에서 이 사실을 사용자에게 경고해야 함.
 */
public abstract class FactorStrategy implements QuantStrategy {

    protected final FundamentalDataService fundamentals;
    protected final KospiUniverseService universeService;

    protected FactorStrategy(FundamentalDataService fundamentals, KospiUniverseService universeService) {
        this.fundamentals = fundamentals;
        this.universeService = universeService;
    }

    @Override
    public List<QuantAsset> defaultUniverse() {
        return universeService.all().stream()
                .map(s -> new QuantAsset(s.code(), s.name(),
                        AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null))
                .toList();
    }

    /** 기본 탑-N. 하위 전략이 override 가능. */
    protected int defaultTopN() { return 20; }

    /** 분기 리밸런싱이 팩터 전략의 강환국 기본값. 월말 여부 + 특정 월만. */
    @Override
    public boolean shouldRebalance(LocalDate prev, LocalDate today, List<LocalDate> availableDates) {
        int m = today.getMonthValue();
        if (m != 3 && m != 6 && m != 9 && m != 12) return false;
        return RebalanceTiming.isLastTradingDayOfMonth(today, availableDates);
    }

    /**
     * 팩터 전략의 핵심 — 유니버스 재무 데이터를 받아 상위 N 종목 + 점수를 반환.
     * Rank 순서대로 리스트, 각 ScoreRow.selected=true.
     */
    protected abstract List<QuantSignal.ScoreRow> selectTopN(List<FundamentalData> universe, int n);

    /**
     * 팩터 전략 공용 generateSignal.
     * 1) 유니버스 펀더멘털 조회 (최신 캐시)
     * 2) selectTopN으로 상위 N 선정
     * 3) 동일가중 부여
     */
    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> priceHistory,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int topN = params.topN(defaultTopN());

        // 유니버스 → 펀더멘털 조회
        List<KospiUniverseService.Stock> src;
        if (universe == null || universe.isEmpty()) {
            src = universeService.all();
        } else {
            src = universe.stream()
                    .map(a -> new KospiUniverseService.Stock(a.getSymbol(), a.getName()))
                    .toList();
        }
        List<FundamentalData> data = fundamentals.getAll(src);

        List<QuantSignal.ScoreRow> ranked = selectTopN(data, topN);

        // 동일가중
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        if (!ranked.isEmpty()) {
            BigDecimal w = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(ranked.size()), 6, RoundingMode.HALF_UP);
            for (QuantSignal.ScoreRow r : ranked) weights.put(r.getSymbol(), w);
        }

        // 진단에 선정 외 상위 5개까지만 비교(ScoreRow) — 너무 길어지는 것 방지
        List<QuantSignal.ScoreRow> diag = new ArrayList<>(ranked);

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(String.format("%s: 유니버스 %d종목 중 상위 %d 동일가중 선정",
                        displayName(), data.size(), ranked.size()))
                .diagnostics(diag)
                .build();
    }

    // ========== 유틸 ==========

    /** 펀더멘털 데이터에서 선택된 지표를 기준으로 오름차순 순위(작을수록 좋음, 예: PER 낮은 게 좋음). */
    protected static Map<String, Integer> rankAsc(List<FundamentalData> data,
                                                   java.util.function.Function<FundamentalData, BigDecimal> metric) {
        return rank(data, metric, true);
    }

    /** 내림차순 순위(클수록 좋음, 예: ROA 높은 게 좋음). */
    protected static Map<String, Integer> rankDesc(List<FundamentalData> data,
                                                    java.util.function.Function<FundamentalData, BigDecimal> metric) {
        return rank(data, metric, false);
    }

    private static Map<String, Integer> rank(List<FundamentalData> data,
                                              java.util.function.Function<FundamentalData, BigDecimal> metric,
                                              boolean ascending) {
        List<FundamentalData> valid = new ArrayList<>();
        for (FundamentalData f : data) {
            BigDecimal v = metric.apply(f);
            // 밸류 팩터는 양수만 의미(음수 PER/PBR는 적자/자본잠식 — 제외)
            if (v != null && v.signum() > 0) valid.add(f);
        }
        valid.sort((a, b) -> ascending
                ? metric.apply(a).compareTo(metric.apply(b))
                : metric.apply(b).compareTo(metric.apply(a)));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < valid.size(); i++) {
            ranks.put(valid.get(i).getStockCode(), i + 1);
        }
        return ranks;
    }

    /** 여러 개별 순위의 합산(낮을수록 좋음). 특정 지표가 없는 종목은 페널티(큰 값) 부여. */
    @SafeVarargs
    protected static List<Map.Entry<String, Integer>> combineRanks(
            int fallbackPenalty, Map<String, Integer>... rankings) {
        Set<String> all = new LinkedHashSet<>();
        for (Map<String, Integer> r : rankings) all.addAll(r.keySet());
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (String code : all) {
            int sum = 0;
            for (Map<String, Integer> r : rankings) {
                sum += r.getOrDefault(code, fallbackPenalty);
            }
            out.add(Map.entry(code, sum));
        }
        out.sort(Map.Entry.comparingByValue());
        return out;
    }

    protected static BigDecimal score(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    protected static FundamentalData findByCode(List<FundamentalData> data, String code) {
        for (FundamentalData f : data) if (f.getStockCode().equals(code)) return f;
        return null;
    }
}

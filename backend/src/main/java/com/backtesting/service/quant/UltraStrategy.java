package com.backtesting.service.quant;

import com.backtesting.config.KisProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.PricePoint;
import com.backtesting.model.quant.FundamentalData;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.MarketSymbol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 울트라 전략 — 강환국의 3단 결합.
 * 밸류 랭크(PER+PBR+PSR+PCR) + 퀄리티 랭크(GP/A+ROE+F-Score) + 모멘텀 랭크(6개월 수익률)
 * 3개 랭크 합산이 가장 작은 상위 N 종목 동일가중.
 *
 * 모멘텀은 KIS 과거 가격에서 직접 계산 (6개월 수익률).
 */
@Slf4j
@Component
public class UltraStrategy extends FactorStrategy {

    private final KisMarketDataService kisMarket;
    private final KisProperties kisProps;

    public UltraStrategy(FundamentalDataService fd, KospiUniverseService us,
                         KisMarketDataService kisMarket, KisProperties kisProps) {
        super(fd, us);
        this.kisMarket = kisMarket;
        this.kisProps = kisProps;
    }

    @Override
    public QuantStrategyType type() { return QuantStrategyType.ULTRA; }

    @Override
    public String displayName() { return "울트라 전략"; }

    @Override
    public String description() {
        return "밸류(PER+PBR+PSR+PCR) + 퀄리티(GP/A+ROE+F-Score) + 모멘텀(6개월 수익률) "
                + "3단 랭크 합산이 작은 상위 N. 강환국의 플래그십 복합 팩터 전략.";
    }

    @Override
    protected int defaultTopN() { return 15; }

    @Override
    protected List<QuantSignal.ScoreRow> selectTopN(List<FundamentalData> data, int n) {
        // 밸류 4개 합산
        Map<String, Integer> per = rankAsc(data, FundamentalData::getPer);
        Map<String, Integer> pbr = rankAsc(data, FundamentalData::getPbr);
        Map<String, Integer> psr = rankAsc(data, FundamentalData::getPsr);
        Map<String, Integer> pcr = rankAsc(data, FundamentalData::getPcr);
        Map<String, Integer> value = mergeSum(data, per, pbr, psr, pcr);

        // 퀄리티 3개 합산(역순: 합이 작을수록 좋음 → rankDesc로 처리하면 rank 작음 = 좋음)
        Map<String, Integer> gpa = rankDesc(data, FundamentalData::getGpa);
        Map<String, Integer> roe = rankDesc(data, FundamentalData::getRoe);
        Map<String, Integer> fsc = rankDesc(data, f ->
                f.getFScore() == null ? null : BigDecimal.valueOf(f.getFScore()));
        Map<String, Integer> quality = mergeSum(data, gpa, roe, fsc);

        // 모멘텀: 6개월 수익률 내림차순 (큰 게 좋음)
        Map<String, BigDecimal> mom = computeMomentum(data);
        Map<String, Integer> momentum = rankMap(data, mom, false);

        // 최종 랭크: value + quality + momentum (모두 rank 작을수록 좋음)
        List<Map.Entry<String, Integer>> combined = combineRanks(data.size() + 1,
                value, quality, momentum);

        List<QuantSignal.ScoreRow> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : combined) {
            if (out.size() >= n) break;
            FundamentalData f = findByCode(data, e.getKey());
            if (f == null) continue;
            BigDecimal mret = mom.get(e.getKey());
            out.add(QuantSignal.ScoreRow.builder()
                    .symbol(f.getStockCode())
                    .label(String.format("%s · V%d + Q%d + M%d = %d (6M %s%%)",
                            f.getName(),
                            value.getOrDefault(e.getKey(), -1),
                            quality.getOrDefault(e.getKey(), -1),
                            momentum.getOrDefault(e.getKey(), -1),
                            e.getValue(),
                            mret == null ? "-" : mret.multiply(BigDecimal.valueOf(100))
                                    .setScale(1, RoundingMode.HALF_UP).toPlainString()))
                    .score(BigDecimal.valueOf(e.getValue()))
                    .selected(true).build());
        }
        return out;
    }

    /** 각 종목 6개월 모멘텀. KIS 과거 6개월 데이터를 페이지네이션으로 조회. 실패 시 null. */
    private Map<String, BigDecimal> computeMomentum(List<FundamentalData> data) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        LocalDate start = LocalDate.now().minusMonths(7);
        for (FundamentalData f : data) {
            try {
                MarketSymbol sym = MarketSymbol.of(AssetType.KR_STOCK, f.getStockCode());
                KisMarketDataService.ChartResult chart = kisMarket.getChartData(sym, start);
                List<PricePoint> ph = chart.priceHistory();
                if (ph.size() < 2) { out.put(f.getStockCode(), null); continue; }
                BigDecimal first = ph.get(0).getClose();
                BigDecimal last = ph.get(ph.size() - 1).getClose();
                if (first == null || first.signum() == 0) { out.put(f.getStockCode(), null); continue; }
                out.put(f.getStockCode(),
                        last.subtract(first).divide(first, 8, RoundingMode.HALF_UP));
            } catch (Exception e) {
                log.debug("Momentum fetch failed {}: {}", f.getStockCode(), e.getMessage());
                out.put(f.getStockCode(), null);
            }
        }
        return out;
    }

    /** 이미 계산된 값 맵을 rank 맵으로 변환. ascending=false이면 큰 게 1위. */
    private static Map<String, Integer> rankMap(List<FundamentalData> data,
                                                 Map<String, BigDecimal> values,
                                                 boolean ascending) {
        List<Map.Entry<String, BigDecimal>> sorted = new ArrayList<>();
        for (FundamentalData f : data) {
            BigDecimal v = values.get(f.getStockCode());
            if (v != null) sorted.add(Map.entry(f.getStockCode(), v));
        }
        sorted.sort((a, b) -> ascending
                ? a.getValue().compareTo(b.getValue())
                : b.getValue().compareTo(a.getValue()));
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) r.put(sorted.get(i).getKey(), i + 1);
        return r;
    }

    @SafeVarargs
    private static Map<String, Integer> mergeSum(List<FundamentalData> data, Map<String, Integer>... ranks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int penalty = data.size() + 1;
        for (FundamentalData f : data) {
            int sum = 0;
            for (Map<String, Integer> r : ranks) sum += r.getOrDefault(f.getStockCode(), penalty);
            out.put(f.getStockCode(), sum);
        }
        return out;
    }
}

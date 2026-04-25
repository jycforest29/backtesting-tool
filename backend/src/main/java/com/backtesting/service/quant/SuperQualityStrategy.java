package com.backtesting.service.quant;

import com.backtesting.model.quant.FundamentalData;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 슈퍼 퀄리티 전략.
 * GP/A(매출총이익/자산), ROE, F-Score 3개 순위 합산(내림차순 = 클수록 좋음)
 * 상위 N 동일가중.
 */
@Component
public class SuperQualityStrategy extends FactorStrategy {

    public SuperQualityStrategy(FundamentalDataService fd, KospiUniverseService us) {
        super(fd, us);
    }

    @Override
    public QuantStrategyType type() { return QuantStrategyType.SUPER_QUALITY; }

    @Override
    public String displayName() { return "슈퍼 퀄리티"; }

    @Override
    public String description() {
        return "GP/A(매출총이익/자산) + ROE + F-Score 3개 퀄리티 지표 순위 합산(높을수록 좋음). "
                + "수익성이 탄탄하고 재무가 건전한 기업 상위 N.";
    }

    @Override
    protected List<QuantSignal.ScoreRow> selectTopN(List<FundamentalData> data, int n) {
        Map<String, Integer> gpaRank = rankDesc(data, FundamentalData::getGpa);
        Map<String, Integer> roeRank = rankDesc(data, FundamentalData::getRoe);
        Map<String, Integer> fScoreRank = rankDesc(data, f ->
                f.getFScore() == null ? null : BigDecimal.valueOf(f.getFScore()));
        List<Map.Entry<String, Integer>> combined = combineRanks(data.size() + 1, gpaRank, roeRank, fScoreRank);

        List<QuantSignal.ScoreRow> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : combined) {
            if (out.size() >= n) break;
            FundamentalData f = findByCode(data, e.getKey());
            if (f == null) continue;
            out.add(QuantSignal.ScoreRow.builder()
                    .symbol(f.getStockCode())
                    .label(String.format("%s · GP/A %s · ROE %s%% · F-Score %s (합산 %d)",
                            f.getName(),
                            num(f.getGpa()),
                            num(f.getRoe()),
                            f.getFScore() == null ? "-" : String.valueOf(f.getFScore()),
                            e.getValue()))
                    .score(BigDecimal.valueOf(e.getValue()))
                    .selected(true).build());
        }
        return out;
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}

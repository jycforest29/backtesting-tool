package com.backtesting.service.quant;

import com.backtesting.model.quant.FundamentalData;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 신마법공식 (강환국 변형).
 * - ROA 순위(높을수록 좋음) + PER 순위(낮을수록 좋음) 합산
 * - 합산 순위 상위 N 동일가중
 * - 분기 리밸런싱
 */
@Component
public class MagicFormulaStrategy extends FactorStrategy {

    public MagicFormulaStrategy(FundamentalDataService fd, KospiUniverseService us) {
        super(fd, us);
    }

    @Override
    public QuantStrategyType type() { return QuantStrategyType.MAGIC_FORMULA; }

    @Override
    public String displayName() { return "신마법공식"; }

    @Override
    public String description() {
        return "ROA(총자산순이익률) 순위 + PER 순위 합산. 합산이 작을수록 '수익성 좋고 싼 기업'. "
                + "강환국이 한국 시장에서 검증한 대표 팩터 전략. 분기 리밸런싱.";
    }

    @Override
    protected List<QuantSignal.ScoreRow> selectTopN(List<FundamentalData> data, int n) {
        Map<String, Integer> roaRank = rankDesc(data, FundamentalData::getRoa);
        Map<String, Integer> perRank = rankAsc(data, FundamentalData::getPer);
        List<Map.Entry<String, Integer>> combined = combineRanks(data.size() + 1, roaRank, perRank);

        List<QuantSignal.ScoreRow> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : combined) {
            if (out.size() >= n) break;
            FundamentalData f = findByCode(data, e.getKey());
            if (f == null) continue;
            out.add(QuantSignal.ScoreRow.builder()
                    .symbol(f.getStockCode())
                    .label(String.format("%s · ROA %s%% · PER %s (합산순위 %d)",
                            f.getName(),
                            f.getRoa() == null ? "-" : f.getRoa().toPlainString(),
                            f.getPer() == null ? "-" : f.getPer().toPlainString(),
                            e.getValue()))
                    .score(java.math.BigDecimal.valueOf(e.getValue()))
                    .selected(true).build());
        }
        return out;
    }
}

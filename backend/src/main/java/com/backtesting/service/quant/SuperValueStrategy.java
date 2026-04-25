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
 * 슈퍼 가치 전략.
 * PER, PBR, PSR, PCR 4개 지표 각각의 순위(낮을수록 좋음) 합산.
 * 단일 지표보다 복합 지표가 한국 시장에서 아웃퍼폼이 검증됨 (강환국).
 */
@Component
public class SuperValueStrategy extends FactorStrategy {

    public SuperValueStrategy(FundamentalDataService fd, KospiUniverseService us) {
        super(fd, us);
    }

    @Override
    public QuantStrategyType type() { return QuantStrategyType.SUPER_VALUE; }

    @Override
    public String displayName() { return "슈퍼 가치"; }

    @Override
    public String description() {
        return "PER + PBR + PSR + PCR 4개 밸류 지표의 순위 합산이 낮은 순(=가장 저평가) "
                + "상위 N 종목 동일가중. 분기 리밸런싱.";
    }

    @Override
    protected List<QuantSignal.ScoreRow> selectTopN(List<FundamentalData> data, int n) {
        Map<String, Integer> per = rankAsc(data, FundamentalData::getPer);
        Map<String, Integer> pbr = rankAsc(data, FundamentalData::getPbr);
        Map<String, Integer> psr = rankAsc(data, FundamentalData::getPsr);
        Map<String, Integer> pcr = rankAsc(data, FundamentalData::getPcr);
        List<Map.Entry<String, Integer>> combined = combineRanks(data.size() + 1, per, pbr, psr, pcr);

        List<QuantSignal.ScoreRow> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : combined) {
            if (out.size() >= n) break;
            FundamentalData f = findByCode(data, e.getKey());
            if (f == null) continue;
            out.add(QuantSignal.ScoreRow.builder()
                    .symbol(f.getStockCode())
                    .label(String.format("%s · PER %s / PBR %s / PSR %s / PCR %s (합산 %d)",
                            f.getName(),
                            num(f.getPer()), num(f.getPbr()), num(f.getPsr()), num(f.getPcr()),
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

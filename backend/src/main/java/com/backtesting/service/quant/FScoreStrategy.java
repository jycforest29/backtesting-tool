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
 * F-Score 전략.
 * Piotroski F-Score 7점 이상 + PBR 낮은 순.
 * 재무 체크리스트를 통과한 '건강한 기업 중 저평가된 것'.
 */
@Component
public class FScoreStrategy extends FactorStrategy {

    private static final int MIN_F_SCORE = 7;

    public FScoreStrategy(FundamentalDataService fd, KospiUniverseService us) {
        super(fd, us);
    }

    @Override
    public QuantStrategyType type() { return QuantStrategyType.F_SCORE; }

    @Override
    public String displayName() { return "F-Score 전략"; }

    @Override
    public String description() {
        return "Piotroski F-Score(0~9) 7점 이상 필터링 후 PBR이 낮은 상위 N 동일가중. "
                + "재무 건전성 9가지 기준 통과한 기업 중 저PBR 선정.";
    }

    @Override
    protected int defaultTopN() { return 20; }

    @Override
    protected List<QuantSignal.ScoreRow> selectTopN(List<FundamentalData> data, int n) {
        // 1단계: F-Score >= MIN_F_SCORE 필터
        List<FundamentalData> passed = new ArrayList<>();
        for (FundamentalData f : data) {
            if (f.getFScore() != null && f.getFScore() >= MIN_F_SCORE
                    && f.getPbr() != null && f.getPbr().signum() > 0) {
                passed.add(f);
            }
        }
        // 2단계: 통과한 것들 중 PBR 오름차순
        passed.sort((a, b) -> a.getPbr().compareTo(b.getPbr()));

        List<QuantSignal.ScoreRow> out = new ArrayList<>();
        for (FundamentalData f : passed) {
            if (out.size() >= n) break;
            out.add(QuantSignal.ScoreRow.builder()
                    .symbol(f.getStockCode())
                    .label(String.format("%s · F-Score %d · PBR %s",
                            f.getName(), f.getFScore(),
                            f.getPbr().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()))
                    .score(BigDecimal.valueOf(f.getFScore()))
                    .selected(true).build());
        }
        return out;
    }
}

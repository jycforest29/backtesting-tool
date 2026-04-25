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
 * 할로윈 전략 (Sell in May).
 * 11월~4월(겨울): 주식 100%
 * 5월~10월(여름): 단기채권/현금 100%
 *
 * 리밸런싱은 4월 말(겨울→여름 전환) / 10월 말(여름→겨울 전환)에만.
 * 즉 연 2회 스위칭.
 */
@Component
public class HalloweenStrategy implements QuantStrategy {

    /** index 0 = 겨울 자산(주식), 1 = 여름 자산(단기채). */
    static final List<QuantAsset> DEFAULT_UNIVERSE = List.of(
            new QuantAsset("069500", "KODEX 200", AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null),
            new QuantAsset("153130", "KODEX 단기채권", AssetType.KR_STOCK, QuantAsset.AssetRole.DEFENSIVE, null)
    );

    @Override
    public QuantStrategyType type() { return QuantStrategyType.HALLOWEEN; }

    @Override
    public String displayName() { return "할로윈 전략"; }

    @Override
    public String description() {
        return "11월~4월 주식 100%, 5월~10월 단기채권 100%. 연 2회 리밸런싱. "
                + "강환국이 한국 시장에서도 유효함을 검증한 계절성 전략.";
    }

    @Override
    public List<QuantAsset> defaultUniverse() { return DEFAULT_UNIVERSE; }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        QuantAsset winter = universe.get(0);
        QuantAsset summer = universe.size() > 1 ? universe.get(1) : null;
        int month = asOfDate.getMonthValue();
        boolean isWinter = month >= 11 || month <= 4;

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        String rationale;
        List<QuantSignal.ScoreRow> diag = new ArrayList<>();
        if (isWinter) {
            weights.put(winter.getSymbol(), BigDecimal.ONE);
            rationale = String.format("겨울 시즌(%d월) → %s 100%%", month, winter.getName());
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(winter.getSymbol()).label("겨울 자산(주식)").score(BigDecimal.ONE).selected(true).build());
        } else {
            if (summer != null) {
                weights.put(summer.getSymbol(), BigDecimal.ONE);
                rationale = String.format("여름 시즌(%d월) → %s 100%%", month, summer.getName());
                diag.add(QuantSignal.ScoreRow.builder()
                        .symbol(summer.getSymbol()).label("여름 자산(방어)").score(BigDecimal.ONE).selected(true).build());
            } else {
                rationale = String.format("여름 시즌(%d월) → 현금 100%%", month);
            }
        }

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(rationale)
                .diagnostics(diag)
                .build();
    }

    @Override
    public boolean shouldRebalance(LocalDate prevRebalance, LocalDate today, List<LocalDate> availableDates) {
        // 4월 말 또는 10월 말의 마지막 거래일에만 리밸런싱
        int m = today.getMonthValue();
        if (m != 4 && m != 10) return false;
        return RebalanceTiming.isLastTradingDayOfMonth(today, availableDates);
    }
}

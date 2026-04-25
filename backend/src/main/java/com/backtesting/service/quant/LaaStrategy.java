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
 * Lethargic Asset Allocation (Keller).
 * 정적 75%: 미국 가치주(IWD), 금(GLD), 중기 미국채(IEF) 각 25%
 * 가변 25%: 미국 대형주(QQQ) 또는 단기채(SHY) — SPY가 10개월 이평선 위면 QQQ, 아래면 SHY.
 *
 * 거래가 거의 없음 (연 1~2회). 강환국이 『거인의 포트폴리오』에서 소개.
 */
@Component
public class LaaStrategy implements QuantStrategy {

    /**
     * role: STATIC = 정적 25% 자산 (3개), VARIABLE = 가변 25% 자산 (QQQ/SHY).
     * 추세 판정(SMA 체크)은 첫 STATIC 자산(=미국 S&P500) 가격으로 수행 — 별도 CANARY 엔트리 없음.
     */
    static final List<QuantAsset> DEFAULT_UNIVERSE = List.of(
            // 정적 3자산
            new QuantAsset("360750", "TIGER 미국S&P500", AssetType.KR_STOCK, QuantAsset.AssetRole.STATIC, null),
            new QuantAsset("132030", "KODEX 골드선물", AssetType.KR_STOCK, QuantAsset.AssetRole.STATIC, null),
            new QuantAsset("136340", "TIGER 미국채10년", AssetType.KR_STOCK, QuantAsset.AssetRole.STATIC, null),
            // 가변 (추세 양호 시 QQQ, 추세 하락 시 단기채)
            new QuantAsset("133690", "TIGER 미국나스닥100", AssetType.KR_STOCK, QuantAsset.AssetRole.VARIABLE, null),
            new QuantAsset("153130", "KODEX 단기채권", AssetType.KR_STOCK, QuantAsset.AssetRole.VARIABLE, null)
    );

    @Override
    public QuantStrategyType type() { return QuantStrategyType.LAA; }

    @Override
    public String displayName() { return "LAA (Lethargic AA)"; }

    @Override
    public String description() {
        return "정적 75% (미국 대형 + 금 + 미국채 각 25%) + 가변 25%. "
                + "SPY가 10개월 이평선 위면 QQQ, 아래면 단기채. 연 1~2회 거래.";
    }

    @Override
    public List<QuantAsset> defaultUniverse() { return DEFAULT_UNIVERSE; }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int smaMonths = params.smaMonths(10);
        List<QuantAsset> statics = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.STATIC).toList();
        List<QuantAsset> variables = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.VARIABLE).toList();
        QuantAsset trendRef = universe.stream()
                .filter(a -> a.getRole() == QuantAsset.AssetRole.CANARY)
                .findFirst().orElse(null);

        // 정적 부분: 각 25% (총 75%)
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        BigDecimal staticWeight = BigDecimal.valueOf(0.25);
        List<QuantSignal.ScoreRow> diag = new ArrayList<>();
        for (QuantAsset s : statics) {
            weights.merge(s.getSymbol(), staticWeight, BigDecimal::add);
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(s.getSymbol()).label("정적 " + s.getName())
                    .score(staticWeight).selected(true).build());
        }

        // 가변 부분 판정
        String trendSymbol = trendRef != null ? trendRef.getSymbol()
                : (!statics.isEmpty() ? statics.get(0).getSymbol() : null);
        BigDecimal sma = trendSymbol != null
                ? QuantIndicators.monthlySma(prices.get(trendSymbol), asOfDate, smaMonths) : null;
        BigDecimal cur = trendSymbol != null
                ? QuantIndicators.closestPriceAtOrBefore(prices.get(trendSymbol), asOfDate) : null;

        boolean trendUp = sma != null && cur != null && cur.compareTo(sma) > 0;
        QuantAsset variableAsset = null;
        if (!variables.isEmpty()) {
            // 규칙: variables[0] = 추세↑ 시 자산, variables[1] = 추세↓ 시 자산
            variableAsset = trendUp ? variables.get(0)
                    : (variables.size() > 1 ? variables.get(1) : variables.get(0));
        }

        BigDecimal variableWeight = BigDecimal.valueOf(0.25);
        if (variableAsset != null) {
            weights.merge(variableAsset.getSymbol(), variableWeight, BigDecimal::add);
        }

        if (cur != null && sma != null) {
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(trendSymbol).label("추세 참조 현재가")
                    .score(cur.setScale(2, RoundingMode.HALF_UP)).selected(false).build());
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(trendSymbol).label("추세 참조 " + smaMonths + "개월 SMA")
                    .score(sma.setScale(2, RoundingMode.HALF_UP)).selected(false).build());
        }
        if (variableAsset != null) {
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(variableAsset.getSymbol())
                    .label("가변 " + variableAsset.getName() + (trendUp ? " (추세↑)" : " (추세↓)"))
                    .score(variableWeight).selected(true).build());
        }

        String rationale = sma == null
                ? "SMA 데이터 부족 → 가변은 추세↑ 기본 선택"
                : (trendUp
                    ? String.format("추세↑ (현재가 > %d개월선) → 정적 75%% + %s 25%%",
                            smaMonths, variableAsset != null ? variableAsset.getName() : "-")
                    : String.format("추세↓ (현재가 < %d개월선) → 정적 75%% + %s 25%% (방어)",
                            smaMonths, variableAsset != null ? variableAsset.getName() : "-"));

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(rationale)
                .diagnostics(diag)
                .build();
    }

    @Override
    public boolean shouldRebalance(LocalDate prev, LocalDate today, List<LocalDate> availableDates) {
        return RebalanceTiming.isLastTradingDayOfMonth(today, availableDates);
    }
}

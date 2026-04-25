package com.backtesting.service.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 전략 공통 지표 계산. 전부 static — 가격 맵(LocalDate -> close)을 입력으로 받는다.
 */
public final class QuantIndicators {

    private QuantIndicators() {}

    /** 오늘 기준 정확히 N개월 전 또는 그 이후 가장 가까운 거래일의 종가. 없으면 null. */
    public static BigDecimal priceMonthsAgo(Map<LocalDate, BigDecimal> prices, LocalDate asOf, int months) {
        LocalDate target = asOf.minusMonths(months);
        return closestPriceAtOrBefore(prices, target);
    }

    /** 주어진 날짜 이하에서 가장 가까운 거래일의 종가. */
    public static BigDecimal closestPriceAtOrBefore(Map<LocalDate, BigDecimal> prices, LocalDate target) {
        if (prices == null || prices.isEmpty()) return null;
        TreeMap<LocalDate, BigDecimal> sorted = prices instanceof TreeMap<LocalDate, BigDecimal> tm
                ? tm : new TreeMap<>(prices);
        Map.Entry<LocalDate, BigDecimal> entry = sorted.floorEntry(target);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * VAA 13612W 가중 모멘텀 스코어:
     * 12*(P/P_1m - 1) + 4*(P/P_3m - 1) + 2*(P/P_6m - 1) + 1*(P/P_12m - 1)
     * 결과는 퍼센트 합 (양수면 상승 모멘텀).
     */
    public static BigDecimal vaaScore(Map<LocalDate, BigDecimal> prices, LocalDate asOf) {
        BigDecimal cur = closestPriceAtOrBefore(prices, asOf);
        if (cur == null) return null;
        BigDecimal p1 = priceMonthsAgo(prices, asOf, 1);
        BigDecimal p3 = priceMonthsAgo(prices, asOf, 3);
        BigDecimal p6 = priceMonthsAgo(prices, asOf, 6);
        BigDecimal p12 = priceMonthsAgo(prices, asOf, 12);
        if (p1 == null || p3 == null || p6 == null || p12 == null) return null;

        double c = cur.doubleValue();
        double score = 12.0 * (c / p1.doubleValue() - 1)
                + 4.0 * (c / p3.doubleValue() - 1)
                + 2.0 * (c / p6.doubleValue() - 1)
                + 1.0 * (c / p12.doubleValue() - 1);
        return BigDecimal.valueOf(score * 100).setScale(4, RoundingMode.HALF_UP);
    }

    /** N개월 총수익률 (소수). (현재가/N개월전가) - 1. 데이터 부족 시 null. */
    public static BigDecimal momentumReturn(Map<LocalDate, BigDecimal> prices, LocalDate asOf, int months) {
        BigDecimal cur = closestPriceAtOrBefore(prices, asOf);
        BigDecimal past = priceMonthsAgo(prices, asOf, months);
        if (cur == null || past == null || past.signum() == 0) return null;
        return cur.subtract(past).divide(past, 8, RoundingMode.HALF_UP);
    }

    /**
     * 월말 SMA. asOf에서 N개월 전까지 월말 종가들의 단순평균.
     * LAA SPY 10개월선 체크용.
     */
    public static BigDecimal monthlySma(Map<LocalDate, BigDecimal> prices, LocalDate asOf, int months) {
        if (prices == null || prices.isEmpty() || months <= 0) return null;
        List<BigDecimal> monthEnds = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            LocalDate target = asOf.minusMonths(i).withDayOfMonth(1).plusMonths(1).minusDays(1);
            BigDecimal v = closestPriceAtOrBefore(prices, target);
            if (v != null) monthEnds.add(v);
        }
        if (monthEnds.size() < months) return null;
        BigDecimal sum = monthEnds.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(monthEnds.size()), 8, RoundingMode.HALF_UP);
    }

    /** 주어진 자산 리스트를 스코어 내림차순으로 정렬 후 상위 N개 반환. */
    public static <T> List<T> topNBy(List<T> items, java.util.function.Function<T, BigDecimal> scoreFn, int n) {
        return items.stream()
                .filter(x -> scoreFn.apply(x) != null)
                .sorted((a, b) -> scoreFn.apply(b).compareTo(scoreFn.apply(a)))
                .limit(n)
                .toList();
    }
}

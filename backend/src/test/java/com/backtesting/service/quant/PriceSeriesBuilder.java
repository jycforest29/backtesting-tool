package com.backtesting.service.quant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 테스트용 합성 가격 시계열 빌더.
 * start ~ end 범위의 평일 가격을 생성. 상승/하락/횡보 시나리오 조합 가능.
 */
public final class PriceSeriesBuilder {

    private PriceSeriesBuilder() {}

    /** 상수 가격 N일치 생성 (룩백 데이터 채울 때). */
    public static Map<LocalDate, BigDecimal> flat(LocalDate start, LocalDate end, double price) {
        Map<LocalDate, BigDecimal> m = new TreeMap<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            if (d.getDayOfWeek().getValue() <= 5) m.put(d, BigDecimal.valueOf(price));
            d = d.plusDays(1);
        }
        return m;
    }

    /** start에서 end까지 시작가 → 종가로 선형 성장. 상승이면 endPrice > startPrice. */
    public static Map<LocalDate, BigDecimal> linear(LocalDate start, LocalDate end,
                                                    double startPrice, double endPrice) {
        Map<LocalDate, BigDecimal> m = new TreeMap<>();
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            if (d.getDayOfWeek().getValue() <= 5) dates.add(d);
            d = d.plusDays(1);
        }
        int n = dates.size();
        for (int i = 0; i < n; i++) {
            double p = startPrice + (endPrice - startPrice) * i / Math.max(1, n - 1);
            m.put(dates.get(i), BigDecimal.valueOf(p));
        }
        return m;
    }
}

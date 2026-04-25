package com.backtesting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 비율 표현. 내부 표현은 "비율 그 자체" (0.02 = 2%) 가 아니라 "퍼센트 값" (2.0 = 2%) 로 저장.
 * 이유: 주식/재무 도메인에서 "2.5%" 식 UI 노출이 많아 그 representation 과 합치시키면
 *       toUi / toUi 변환 실수 빈도가 낮아짐.
 *
 * toFraction() 으로 0.025 변환 가능.
 */
public record Percentage(BigDecimal percent) {

    public Percentage {
        if (percent == null) throw new IllegalArgumentException("percent required");
    }

    public static Percentage of(String pct)          { return new Percentage(new BigDecimal(pct)); }
    public static Percentage of(double pct)          { return new Percentage(BigDecimal.valueOf(pct)); }
    public static Percentage fromFraction(double f)  { return new Percentage(BigDecimal.valueOf(f * 100.0)); }

    /** 0.025 = 2.5% 형태로 반환. */
    public BigDecimal toFraction() {
        return percent.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_EVEN);
    }

    public Percentage negate() { return new Percentage(percent.negate()); }

    public boolean isPositive() { return percent.signum() > 0; }
    public boolean isNegative() { return percent.signum() < 0; }

    @Override public String toString() { return percent.toPlainString() + "%"; }
}

package com.backtesting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 금액 값 객체. 통화가 다른 Money 끼리는 산술 불가 (컴파일 시 방지).
 *
 * 설계 노트:
 *  - BigDecimal 로 정밀도 보존. long 센트/원 단위로 하면 JPY·USD 혼재 시 소수점 문제.
 *  - 비교: compareTo 는 scale 을 무시 — "10" 과 "10.00" 은 동등 처리.
 *  - 연산 결과의 스케일은 피연산자 중 큰 쪽 기준. round() 로 명시적 조정 가능.
 *  - KRW 는 소수점 0자리, USD 는 2자리, JPY 는 0자리 — Currency.getDefaultFractionDigits 사용.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance(currencyCode));
    }

    public static Money krw(long amount) { return of(amount, "KRW"); }
    public static Money usd(String amount) { return of(amount, "USD"); }
    public static Money zero(String currencyCode) { return of(0L, currencyCode); }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money round() {
        return new Money(amount.setScale(currency.getDefaultFractionDigits(),
                RoundingMode.HALF_EVEN), currency);
    }

    public boolean isNegative()   { return amount.signum() < 0; }
    public boolean isPositive()   { return amount.signum() > 0; }
    public boolean isZero()       { return amount.signum() == 0; }
    public boolean isGreaterThan(Money other) { requireSameCurrency(other); return amount.compareTo(other.amount) > 0; }
    public boolean isLessThan(Money other)    { requireSameCurrency(other); return amount.compareTo(other.amount) < 0; }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}

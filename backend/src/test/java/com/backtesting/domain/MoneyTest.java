package com.backtesting.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void krwHasNoFractionDigits() {
        Money m = Money.krw(1234567);
        assertThat(m.currency().getDefaultFractionDigits()).isZero();
        assertThat(m.round().amount()).isEqualTo(new BigDecimal("1234567"));
    }

    @Test
    void usdHasTwoFractionDigits() {
        Money usd = Money.usd("123.456");
        assertThat(usd.round().amount()).isEqualTo(new BigDecimal("123.46"));
    }

    @Test
    void addAndSubtractPreserveCurrency() {
        Money a = Money.krw(1_000);
        Money b = Money.krw(300);
        assertThat(a.add(b).amount()).isEqualTo(new BigDecimal("1300"));
        assertThat(a.subtract(b).amount()).isEqualTo(new BigDecimal("700"));
    }

    @Test
    void crossCurrencyArithmeticIsRejected() {
        Money krw = Money.krw(1_000);
        Money usd = Money.usd("10");
        assertThatThrownBy(() -> krw.add(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void comparisonsIgnoreScale() {
        Money a = Money.of("10.00", "USD");
        Money b = Money.of("10",    "USD");
        assertThat(a.isGreaterThan(b)).isFalse();
        assertThat(b.isLessThan(a)).isFalse();
    }

    @Test
    void signAndZeroChecks() {
        assertThat(Money.zero("KRW").isZero()).isTrue();
        assertThat(Money.krw(-1).isNegative()).isTrue();
        assertThat(Money.krw(1).isPositive()).isTrue();
    }

    @Test
    void multiplyPreservesCurrency() {
        Money m = Money.krw(1_000).multiply(new BigDecimal("1.5"));
        assertThat(m.amount()).isEqualTo(new BigDecimal("1500.0"));
    }
}

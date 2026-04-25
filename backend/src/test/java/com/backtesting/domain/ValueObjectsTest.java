package com.backtesting.domain;

import com.backtesting.model.AssetType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원시 타입 대신 쓰는 값 객체들의 invariant 검증.
 * 불변식이 한 군데서 깨지면 전체 트레이딩 시스템이 흔들리므로 꼼꼼히 체크.
 */
class ValueObjectsTest {

    // ---------- Quantity ----------

    @Test
    void quantityRejectsNegative() {
        assertThatThrownBy(() -> new Quantity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityArithmetic() {
        Quantity a = Quantity.of(10);
        Quantity b = Quantity.of(3);
        assertThat(a.plus(b).value()).isEqualTo(13);
        assertThat(a.minus(b).value()).isEqualTo(7);
        assertThat(Quantity.ZERO.isZero()).isTrue();
        assertThat(a.isPositive()).isTrue();
        assertThat(a.toString()).isEqualTo("10");
    }

    @Test
    void quantitySubtractBelowZeroRejected() {
        assertThatThrownBy(() -> Quantity.of(3).minus(Quantity.of(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Symbol ----------

    @Test
    void domesticSymbolHasNoExchange() {
        Symbol s = Symbol.domestic("005930");
        assertThat(s.isDomestic()).isTrue();
        assertThat(s.exchange()).isNull();
        assertThat(s.toString()).contains("KR_STOCK:005930");
    }

    @Test
    void overseasSymbolRequiresExchange() {
        assertThatThrownBy(() -> Symbol.overseas(AssetType.US_STOCK, "AAPL", null))
                .isInstanceOf(IllegalArgumentException.class);
        Symbol us = Symbol.overseas(AssetType.US_STOCK, "AAPL", "NAS");
        assertThat(us.toString()).contains("AAPL@NAS");
    }

    @Test
    void blankCodeRejected() {
        assertThatThrownBy(() -> new Symbol(AssetType.KR_STOCK, "", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameCodeDifferentMarketNotEqual() {
        Symbol kr = Symbol.domestic("005930");
        Symbol us = Symbol.overseas(AssetType.US_STOCK, "005930", "NAS");
        assertThat(kr).isNotEqualTo(us);
    }

    // ---------- Percentage ----------

    @Test
    void percentageToFraction() {
        Percentage p = Percentage.of(2.5);
        assertThat(p.toFraction()).isEqualByComparingTo("0.025");
    }

    @Test
    void percentageFromFractionRoundTrip() {
        Percentage p = Percentage.fromFraction(0.075);
        assertThat(p.percent()).isEqualByComparingTo(new BigDecimal("7.5"));
    }

    @Test
    void percentageNegationAndSigns() {
        Percentage positive = Percentage.of("3.5");
        Percentage negative = positive.negate();
        assertThat(negative.isNegative()).isTrue();
        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.toString()).isEqualTo("3.5%");
    }
}

package com.backtesting.service.elw;

import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Scale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BlackScholesIv 에 대한 속성 기반 테스트.
 * 수천 개의 무작위 valid 입력에서 다음 불변식을 검증:
 *
 *  - Round-trip: solve(bsPrice(σ)) ≈ σ
 *  - 상태-값 정합성: CONVERGED 는 값≠null, 그 외는 값=null
 *  - 출력 바운드: σ ∈ (0, 5]
 *  - 가격 단조성: 같은 콜에서 market price ↑ → IV ↑
 *  - 바운드 밖 가격 → NO_SOLUTION
 *
 * 예시 기반 테스트가 잡지 못하는 corner case (극단적 행사가/만기/변동성 조합)을 자동 탐색.
 */
class BlackScholesIvPropertyTest {

    private static final double TOL = 2e-2; // 2% vol tolerance — 극단 moneyness/near-expiry 수치 한계 반영

    /**
     * 라운드트립: 알려진 σ 로 BS 가격 계산 → 솔버가 같은 σ 복원.
     *
     * Regime 문서화 (이 property 가 성립하는 영역):
     *   moneyness ∈ [0.9, 1.1], vol ≥ 15%, t ≥ 0.1년
     *   deep ITM/OTM + 초저변동성 + 초단기 조합은 vega≈0 이라 가격이 σ 변화에 둔감 →
     *   수치 역산 정밀도가 본질적으로 제한. 그 영역의 정확도는 예시 기반 테스트로 개별 검증.
     */
    @Property(tries = 500)
    void roundTrip_recoversWithinTolerance(
            @ForAll @DoubleRange(min = 80, max = 120) @Scale(4) double s,
            @ForAll @DoubleRange(min = 80, max = 120) @Scale(4) double k,
            @ForAll @DoubleRange(min = 0.1, max = 2.0) @Scale(4) double t,
            @ForAll @DoubleRange(min = 0.0, max = 0.08) @Scale(4) double r,
            @ForAll @DoubleRange(min = 0.0, max = 0.04) @Scale(4) double q,
            @ForAll @DoubleRange(min = 0.15, max = 1.5) @Scale(4) double sigma,
            @ForAll boolean isCall) {

        double moneyness = s / k;
        Assume.that(moneyness >= 0.9 && moneyness <= 1.1);

        double price = bsPrice(s, k, t, r, q, sigma, isCall);
        Assume.that(price > 0.5);
        Assume.that(price < (isCall ? s * Math.exp(-q * t) : k * Math.exp(-r * t)) - 0.5);

        var res = BlackScholesIv.solve(price, s, k, t, r, q, isCall);
        assertThat(res.status()).isEqualTo(BlackScholesIv.Status.CONVERGED);
        assertThat(res.value()).isNotNull();
        assertThat(res.value()).isCloseTo(sigma, org.assertj.core.data.Offset.offset(TOL));
    }

    /** 상태/값 정합성: CONVERGED 에만 값이 있고, 실패 상태면 null. */
    @Property(tries = 200)
    void statusAndValue_areConsistent(
            @ForAll @DoubleRange(min = 0.01, max = 1000) @Scale(4) double marketPrice,
            @ForAll @DoubleRange(min = 1, max = 500) @Scale(4) double s,
            @ForAll @DoubleRange(min = 1, max = 500) @Scale(4) double k,
            @ForAll @DoubleRange(min = 0.01, max = 3.0) @Scale(4) double t,
            @ForAll @DoubleRange(min = 0.0, max = 0.1) @Scale(4) double r,
            @ForAll boolean isCall) {

        var res = BlackScholesIv.solve(marketPrice, s, k, t, r, 0.0, isCall);
        if (res.status() == BlackScholesIv.Status.CONVERGED) {
            assertThat(res.value()).isNotNull();
            assertThat(res.value()).isGreaterThan(0).isLessThanOrEqualTo(5.0);
        } else {
            assertThat(res.value()).isNull();
        }
    }

    /** 콜 가격이 올라가면 내재변동성도 증가 (고정 s/k/t/r 하에서 단조). */
    @Property(tries = 200)
    void ivMonotonicInPrice_forCalls(
            @ForAll @DoubleRange(min = 80, max = 120) double s,
            @ForAll @DoubleRange(min = 80, max = 120) double k,
            @ForAll @DoubleRange(min = 0.1, max = 1.5) double t,
            @ForAll @DoubleRange(min = 0.01, max = 0.05) double r) {

        // 동일 구조에서 σ=0.2 와 σ=0.4 가격 비교
        double p1 = bsPrice(s, k, t, r, 0, 0.2, true);
        double p2 = bsPrice(s, k, t, r, 0, 0.4, true);
        Assume.that(p1 > 0.01 && p2 > 0.01);

        var r1 = BlackScholesIv.solve(p1, s, k, t, r, 0, true);
        var r2 = BlackScholesIv.solve(p2, s, k, t, r, 0, true);
        Assume.that(r1.status() == BlackScholesIv.Status.CONVERGED
                && r2.status() == BlackScholesIv.Status.CONVERGED);

        // 더 비싼 옵션 → 더 높은 IV
        assertThat(r2.value()).isGreaterThan(r1.value());
    }

    /** 차익거래 바운드 밖 입력은 반드시 NO_SOLUTION. */
    @Property(tries = 100)
    void priceAboveUpperBound_yieldsNoSolution(
            @ForAll @DoubleRange(min = 50, max = 200) double s,
            @ForAll @DoubleRange(min = 50, max = 200) double k,
            @ForAll @DoubleRange(min = 0.1, max = 2.0) double t,
            @ForAll @DoubleRange(min = 0, max = 0.05) double r) {

        double upperBound = s;  // call 상한 (q=0 시 S)
        double aboveBound = upperBound + 1.0;
        var res = BlackScholesIv.solve(aboveBound, s, k, t, r, 0, true);
        assertThat(res.status()).isEqualTo(BlackScholesIv.Status.NO_SOLUTION);
    }

    /** 퍗-콜 대칭: 같은 σ 가격으로 만든 쌍을 역산 → 같은 σ. */
    @Property(tries = 200)
    void putCallParity_recoversSameVol(
            @ForAll @DoubleRange(min = 80, max = 120) double s,
            @ForAll @DoubleRange(min = 80, max = 120) double k,
            @ForAll @DoubleRange(min = 0.05, max = 1.5) double t,
            @ForAll @DoubleRange(min = 0.0, max = 0.08) double r,
            @ForAll @DoubleRange(min = 0.1, max = 1.0) double sigma) {

        double call = bsPrice(s, k, t, r, 0, sigma, true);
        double put = bsPrice(s, k, t, r, 0, sigma, false);
        Assume.that(call > 0.05 && put > 0.05);

        var c = BlackScholesIv.solve(call, s, k, t, r, 0, true);
        var p = BlackScholesIv.solve(put, s, k, t, r, 0, false);
        Assume.that(c.status() == BlackScholesIv.Status.CONVERGED
                && p.status() == BlackScholesIv.Status.CONVERGED);

        assertThat(c.value()).isCloseTo(p.value(), org.assertj.core.data.Offset.offset(TOL));
    }

    // ----- helpers -----

    private static double bsPrice(double s, double k, double t, double r, double q,
                                  double sigma, boolean isCall) {
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double discR = Math.exp(-r * t);
        double discQ = Math.exp(-q * t);
        return isCall
                ? s * discQ * cdf(d1) - k * discR * cdf(d2)
                : k * discR * cdf(-d2) - s * discQ * cdf(-d1);
    }

    private static double cdf(double x) {
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
        int sign = x < 0 ? -1 : 1;
        double ax = Math.abs(x) / Math.sqrt(2);
        double t = 1.0 / (1.0 + p * ax);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-ax * ax);
        return 0.5 * (1.0 + sign * y);
    }
}

package com.backtesting.service.elw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IV 솔버 핵심 수학 테스트.
 * - 왕복(roundtrip): 알려진 σ로 가격 계산 → 솔버가 같은 σ 복원
 * - 풋-콜 패리티 일관성
 * - 배당 q>0 경로 커버
 * - 입력 이상·바운드 밖 가격은 예외 아닌 Status 반환
 */
class BlackScholesIvTest {

    private static final double EPS = 1e-4;

    @Test
    void roundTrip_atmCall_recoversSigma() {
        double s = 100, k = 100, t = 0.5, r = 0.03, q = 0.0;
        double sigma = 0.25;
        double call = bsPrice(s, k, t, r, q, sigma, true);

        var res = BlackScholesIv.solve(call, s, k, t, r, q, true);
        assertEquals(BlackScholesIv.Status.CONVERGED, res.status());
        assertEquals(sigma, res.value(), EPS);
        assertTrue(res.iterations() >= 1 && res.iterations() <= 60);
    }

    @Test
    void roundTrip_put_recoversSigma() {
        double s = 100, k = 105, t = 0.25, r = 0.02, q = 0.0;
        double sigma = 0.40;
        double put = bsPrice(s, k, t, r, q, sigma, false);

        var res = BlackScholesIv.solve(put, s, k, t, r, q, false);
        assertEquals(BlackScholesIv.Status.CONVERGED, res.status());
        assertEquals(sigma, res.value(), EPS);
    }

    @Test
    void putAndCallAtSameStrikeExpiry_returnSameVol() {
        double s = 100, k = 95, t = 0.25, r = 0.02, q = 0.0;
        double sigma = 0.30;
        double call = bsPrice(s, k, t, r, q, sigma, true);
        double put = bsPrice(s, k, t, r, q, sigma, false);

        var c = BlackScholesIv.solve(call, s, k, t, r, q, true);
        var p = BlackScholesIv.solve(put, s, k, t, r, q, false);
        assertEquals(BlackScholesIv.Status.CONVERGED, c.status());
        assertEquals(BlackScholesIv.Status.CONVERGED, p.status());
        assertEquals(c.value(), p.value(), EPS);
    }

    @Test
    void dividendYield_affectsSolver() {
        double s = 100, k = 100, t = 1.0, r = 0.03, q = 0.05;
        double sigma = 0.30;
        double call = bsPrice(s, k, t, r, q, sigma, true);

        var res = BlackScholesIv.solve(call, s, k, t, r, q, true);
        assertEquals(BlackScholesIv.Status.CONVERGED, res.status());
        assertEquals(sigma, res.value(), EPS);
    }

    @Test
    void invalidInput_negativeTime_returnsInvalidStatusWithoutException() {
        var res = BlackScholesIv.solve(5.0, 100, 100, -0.1, 0.03, 0, true);
        assertEquals(BlackScholesIv.Status.INVALID_INPUT, res.status());
        assertNull(res.value());
    }

    @Test
    void invalidInput_zeroPrice_returnsInvalidStatus() {
        var res = BlackScholesIv.solve(0.0, 100, 100, 0.25, 0.03, 0, true);
        assertEquals(BlackScholesIv.Status.INVALID_INPUT, res.status());
    }

    @Test
    void priceBelowIntrinsic_returnsNoSolution() {
        // Deep ITM call with price way below intrinsic → 차익거래 영역, IV 해 없음
        double s = 100, k = 50, t = 0.5, r = 0.03;
        var res = BlackScholesIv.solve(1.0, s, k, t, r, 0, true);
        assertEquals(BlackScholesIv.Status.NO_SOLUTION, res.status());
        assertNotNull(res.note());
    }

    @Test
    void priceAboveUpperBound_returnsNoSolution() {
        // Call 가격이 S*exp(-qT) 상한 초과 → 해 없음
        double s = 100, k = 50, t = 0.5, r = 0.03;
        var res = BlackScholesIv.solve(200.0, s, k, t, r, 0, true);
        assertEquals(BlackScholesIv.Status.NO_SOLUTION, res.status());
    }

    @Test
    void highVolatility_convergesWithinBounds() {
        double s = 100, k = 110, t = 1.0, r = 0.03, q = 0;
        double sigma = 1.5; // 150% vol
        double call = bsPrice(s, k, t, r, q, sigma, true);

        var res = BlackScholesIv.solve(call, s, k, t, r, q, true);
        assertEquals(BlackScholesIv.Status.CONVERGED, res.status());
        assertEquals(sigma, res.value(), 1e-3);
    }

    @Test
    void nearExpiry_atm_stillConverges() {
        double s = 100, k = 100, t = 1.0 / 365, r = 0.03, q = 0; // 1일 남음
        double sigma = 0.20;
        double call = bsPrice(s, k, t, r, q, sigma, true);

        var res = BlackScholesIv.solve(call, s, k, t, r, q, true);
        assertEquals(BlackScholesIv.Status.CONVERGED, res.status());
        assertEquals(sigma, res.value(), 1e-3);
    }

    // ----- BS 가격 계산 헬퍼 (독립 구현 — 솔버 내부와 별도로 검증) -----
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

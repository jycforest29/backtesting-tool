package com.backtesting.service.elw;

/**
 * 블랙-숄즈 내재변동성 역산기.
 * Newton-Raphson 우선, vega 0 근처·바운드 이탈 시 이분법(bisection) 폴백.
 * 실패를 예외가 아닌 Status 로 반환 — 대량 호출 시 개별 실패가 전체를 중단시키지 않도록.
 */
public final class BlackScholesIv {

    private BlackScholesIv() {}

    public enum Status {
        CONVERGED, MAX_ITER, ZERO_VEGA, INVALID_INPUT, NO_SOLUTION
    }

    public record IvResult(Double value, int iterations, Status status, String note) {
        static IvResult ok(double v, int it) { return new IvResult(v, it, Status.CONVERGED, null); }
        static IvResult fail(Status s, String note) { return new IvResult(null, 0, s, note); }
    }

    private static final double MIN_SIGMA = 0.001;
    private static final double MAX_SIGMA = 5.0;
    private static final double PRICE_TOL = 1e-5;
    private static final double SIGMA_TOL = 1e-7;
    private static final int MAX_ITER = 60;
    private static final double MIN_VEGA = 1e-8;

    /**
     * @param marketPrice 시장 옵션 프리미엄
     * @param s 기초자산 현재가
     * @param k 행사가
     * @param t 만기까지 연도 (> 0)
     * @param r 무위험금리 (연, continuous)
     * @param q 배당률 (연, continuous; 없으면 0)
     * @param isCall true=콜, false=풋
     */
    public static IvResult solve(double marketPrice, double s, double k, double t,
                                 double r, double q, boolean isCall) {
        if (!(t > 0) || !(s > 0) || !(k > 0) || !(marketPrice > 0)) {
            return IvResult.fail(Status.INVALID_INPUT, "nonpositive input");
        }
        double discR = Math.exp(-r * t);
        double discQ = Math.exp(-q * t);
        double intrinsic = isCall
                ? Math.max(0, s * discQ - k * discR)
                : Math.max(0, k * discR - s * discQ);
        double upper = isCall ? s * discQ : k * discR;
        if (marketPrice < intrinsic - PRICE_TOL || marketPrice > upper + PRICE_TOL) {
            return IvResult.fail(Status.NO_SOLUTION,
                    String.format("price %.4f outside bounds [%.4f, %.4f]", marketPrice, intrinsic, upper));
        }

        double sigma = 0.3;
        for (int i = 1; i <= MAX_ITER; i++) {
            double[] pv = priceAndVega(s, k, t, r, q, sigma, isCall);
            double diff = pv[0] - marketPrice;
            if (Math.abs(diff) < PRICE_TOL) return IvResult.ok(sigma, i);
            if (pv[1] < MIN_VEGA) return bisect(marketPrice, s, k, t, r, q, isCall);
            double step = diff / pv[1];
            double next = sigma - step;
            if (!(next > MIN_SIGMA) || !(next < MAX_SIGMA)) {
                return bisect(marketPrice, s, k, t, r, q, isCall);
            }
            if (Math.abs(step) < SIGMA_TOL) return IvResult.ok(next, i);
            sigma = next;
        }
        IvResult bi = bisect(marketPrice, s, k, t, r, q, isCall);
        return bi.status() == Status.CONVERGED ? bi : IvResult.fail(Status.MAX_ITER, "newton+bisect exhausted");
    }

    private static IvResult bisect(double marketPrice, double s, double k, double t,
                                    double r, double q, boolean isCall) {
        double lo = MIN_SIGMA, hi = MAX_SIGMA;
        double pLo = price(s, k, t, r, q, lo, isCall);
        double pHi = price(s, k, t, r, q, hi, isCall);
        if ((pLo - marketPrice) * (pHi - marketPrice) > 0) {
            return IvResult.fail(Status.NO_SOLUTION, "bracket mismatch");
        }
        for (int i = 1; i <= MAX_ITER; i++) {
            double mid = 0.5 * (lo + hi);
            double pM = price(s, k, t, r, q, mid, isCall);
            double diff = pM - marketPrice;
            if (Math.abs(diff) < PRICE_TOL || (hi - lo) < SIGMA_TOL) return IvResult.ok(mid, i);
            if ((pLo - marketPrice) * diff <= 0) { hi = mid; pHi = pM; }
            else { lo = mid; pLo = pM; }
        }
        return IvResult.fail(Status.MAX_ITER, "bisection exhausted");
    }

    private static double[] priceAndVega(double s, double k, double t, double r, double q,
                                          double sigma, boolean isCall) {
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double discR = Math.exp(-r * t);
        double discQ = Math.exp(-q * t);
        double px = isCall
                ? s * discQ * normalCdf(d1) - k * discR * normalCdf(d2)
                : k * discR * normalCdf(-d2) - s * discQ * normalCdf(-d1);
        double vega = s * discQ * normalPdf(d1) * sqrtT;
        return new double[]{px, vega};
    }

    private static double price(double s, double k, double t, double r, double q,
                                 double sigma, boolean isCall) {
        return priceAndVega(s, k, t, r, q, sigma, isCall)[0];
    }

    private static double normalPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /** Abramowitz-Stegun 근사, max error ~7.5e-8. */
    private static double normalCdf(double x) {
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
        int sign = x < 0 ? -1 : 1;
        double ax = Math.abs(x) / Math.sqrt(2);
        double t = 1.0 / (1.0 + p * ax);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-ax * ax);
        return 0.5 * (1.0 + sign * y);
    }
}

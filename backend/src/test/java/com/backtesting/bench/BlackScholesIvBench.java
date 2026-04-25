package com.backtesting.bench;

import com.backtesting.service.elw.BlackScholesIv;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmark — IV 솔버 hot path.
 *
 * 실행 (수동):
 *   mvn test-compile
 *   java -cp "target/test-classes:target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)" \
 *        com.backtesting.bench.BlackScholesIvBench
 *
 * 또는 IDE 에서 main() 실행.
 *
 * 측정 regime:
 *   - ATM (moneyness 1.0, 6개월) — 일반 케이스
 *   - OTM (moneyness 1.2, 3개월) — Newton 이 bisection 폴백할 수 있는 케이스
 *   - Near-expiry (moneyness 1.0, 7일) — d1/d2 분산 큰 극단 케이스
 *
 * 측정 지표:
 *   - Mode.AverageTime (ns/op) — 한 솔브에 걸리는 시간
 *   - Mode.Throughput (ops/sec) — 초당 솔브 수 — ELW 체인 N개 병렬 처리 성능 예측
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@State(Scope.Benchmark)
public class BlackScholesIvBench {

    private double atmPrice;
    private double otmPrice;
    private double nearExpPrice;

    @Setup
    public void setup() {
        atmPrice = bsPrice(100, 100, 0.5, 0.03, 0, 0.25, true);
        otmPrice = bsPrice(100, 120, 0.25, 0.03, 0, 0.30, true);
        nearExpPrice = bsPrice(100, 100, 7.0 / 365, 0.03, 0, 0.20, true);
    }

    @Benchmark
    public BlackScholesIv.IvResult atmCall() {
        return BlackScholesIv.solve(atmPrice, 100, 100, 0.5, 0.03, 0, true);
    }

    @Benchmark
    public BlackScholesIv.IvResult otmCall() {
        return BlackScholesIv.solve(otmPrice, 100, 120, 0.25, 0.03, 0, true);
    }

    @Benchmark
    public BlackScholesIv.IvResult nearExpiry() {
        return BlackScholesIv.solve(nearExpPrice, 100, 100, 7.0 / 365, 0.03, 0, true);
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(BlackScholesIvBench.class.getSimpleName())
                .build()).run();
    }

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

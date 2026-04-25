package com.backtesting.testsupport;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 결정론적 fault injector — unit/integration 테스트용.
 *
 * 지원 fault:
 *  - LATENCY_MS: 지정 ms 지연
 *  - FAIL_RATE: 주어진 확률로 예외
 *  - CONNECTION_RESET: 특정 호출 번호에서 IOException
 *  - PARTIAL_RESPONSE: 응답 byte 일부만 전달 (truncation)
 *
 * seed 로 재현성 보장 — property test / DST 와 조합 시 동일 seed → 동일 fault 시퀀스.
 */
public final class FaultInjector {

    public enum FaultType {
        NONE,
        LATENCY,
        EXCEPTION,
        CONNECTION_RESET,
        PARTIAL_RESPONSE
    }

    public record Fault(FaultType type, int param, String message) {
        public static Fault none() { return new Fault(FaultType.NONE, 0, null); }
        public static Fault latency(int ms) { return new Fault(FaultType.LATENCY, ms, null); }
        public static Fault exception(String msg) { return new Fault(FaultType.EXCEPTION, 0, msg); }
        public static Fault reset() { return new Fault(FaultType.CONNECTION_RESET, 0, "connection reset"); }
        public static Fault partial(int bytes) { return new Fault(FaultType.PARTIAL_RESPONSE, bytes, null); }
    }

    private final Random rng;
    private final AtomicInteger callCount = new AtomicInteger();

    /** 콜별 fault 확률 (0.0 ~ 1.0). 0 이면 fault 없음. */
    private final double faultRate;
    /** 모든 fault 중 특정 type 의 상대 가중치. */
    private final double[] typeWeights;

    public FaultInjector(long seed, double faultRate,
                         double latencyWeight, double exceptionWeight,
                         double resetWeight, double partialWeight) {
        this.rng = new Random(seed);
        this.faultRate = faultRate;
        this.typeWeights = new double[]{latencyWeight, exceptionWeight, resetWeight, partialWeight};
    }

    public int callCount() { return callCount.get(); }

    public Fault next() {
        callCount.incrementAndGet();
        if (rng.nextDouble() >= faultRate) return Fault.none();

        double total = 0;
        for (double w : typeWeights) total += w;
        if (total <= 0) return Fault.none();

        double pick = rng.nextDouble() * total;
        double cum = 0;
        int chosen = 0;
        for (int i = 0; i < typeWeights.length; i++) {
            cum += typeWeights[i];
            if (pick < cum) { chosen = i; break; }
        }
        return switch (chosen) {
            case 0 -> Fault.latency(50 + rng.nextInt(500));         // 50~550ms
            case 1 -> Fault.exception("injected failure " + rng.nextInt(10000));
            case 2 -> Fault.reset();
            default -> Fault.partial(8 + rng.nextInt(120));
        };
    }

    /** Fault 실행 — latency/exception 모두 이 메서드 내부에서 처리. */
    public void apply(Fault f) throws Exception {
        switch (f.type()) {
            case NONE -> { /* noop */ }
            case LATENCY -> Thread.sleep(f.param());
            case EXCEPTION -> throw new RuntimeException(f.message());
            case CONNECTION_RESET -> throw new java.io.IOException(f.message());
            case PARTIAL_RESPONSE -> throw new java.io.EOFException(
                    "response truncated at " + f.param() + " bytes");
        }
    }

    /** 편의 메서드 — 랜덤 fault 생성+적용을 한 번에. */
    public void injectAndMaybeThrow() throws Exception {
        apply(next());
    }

    /** 시드 없는 non-deterministic 버전 — 가벼운 ad-hoc 테스트용. */
    public static FaultInjector random(double rate) {
        return new FaultInjector(ThreadLocalRandom.current().nextLong(),
                rate, 0.3, 0.4, 0.15, 0.15);
    }
}

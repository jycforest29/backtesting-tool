package com.backtesting.testsupport;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault injector 자체의 결정론성 + 분포 검증.
 */
class FaultInjectorTest {

    @Test
    void sameSeed_sameSequence() {
        FaultInjector a = new FaultInjector(42L, 0.5, 1, 1, 1, 1);
        FaultInjector b = new FaultInjector(42L, 0.5, 1, 1, 1, 1);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.next().type(), b.next().type(),
                    "같은 seed 는 동일 fault 시퀀스 생성 (재현성)");
        }
    }

    @Test
    void differentSeeds_differentSequences() {
        FaultInjector a = new FaultInjector(1L, 0.9, 1, 1, 1, 1);
        FaultInjector b = new FaultInjector(2L, 0.9, 1, 1, 1, 1);
        int diffs = 0;
        for (int i = 0; i < 100; i++) {
            if (a.next().type() != b.next().type()) diffs++;
        }
        assertTrue(diffs > 30, "다른 seed 는 확률적으로 다른 시퀀스");
    }

    @Test
    void zeroFaultRate_alwaysNone() {
        FaultInjector f = new FaultInjector(1L, 0.0, 1, 1, 1, 1);
        for (int i = 0; i < 50; i++) {
            assertEquals(FaultInjector.FaultType.NONE, f.next().type());
        }
    }

    @Test
    void distributionRespectsWeights() {
        // exception 만 100%, 나머지 0 → fault 발생 시 모두 EXCEPTION
        FaultInjector f = new FaultInjector(12345L, 1.0, 0, 1, 0, 0);
        Map<FaultInjector.FaultType, Integer> counts = new EnumMap<>(FaultInjector.FaultType.class);
        for (int i = 0; i < 200; i++) {
            counts.merge(f.next().type(), 1, Integer::sum);
        }
        assertEquals(200, counts.getOrDefault(FaultInjector.FaultType.EXCEPTION, 0));
    }

    @Test
    void latencyFault_sleepsApproximately() throws Exception {
        FaultInjector f = new FaultInjector(1L, 1.0, 1, 0, 0, 0);
        long start = System.nanoTime();
        f.injectAndMaybeThrow();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 30, "latency fault 는 최소 50ms 이상 대기 (param 50+ms)");
    }

    @Test
    void exceptionFault_throws() {
        FaultInjector f = new FaultInjector(1L, 1.0, 0, 1, 0, 0);
        assertThrows(RuntimeException.class, f::injectAndMaybeThrow);
    }
}

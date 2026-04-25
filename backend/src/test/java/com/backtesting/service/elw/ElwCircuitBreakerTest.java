package com.backtesting.service.elw;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서킷 브레이커 상태 전이 + 동시성 불변식 검증.
 * 쿨다운은 리플렉션으로 openedAt 조작해 시간 경과를 시뮬레이트 (Thread.sleep 회피).
 */
class ElwCircuitBreakerTest {

    private final ElwCircuitBreaker cb = new ElwCircuitBreaker();

    @Test
    void startsClosed() {
        assertEquals(ElwCircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void success_passesThrough() {
        Optional<String> r = cb.protect(() -> "ok");
        assertTrue(r.isPresent());
        assertEquals("ok", r.get());
        assertEquals(ElwCircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void singleException_swallowed_noStateChange() {
        Optional<String> r = cb.protect(() -> { throw new RuntimeException("boom"); });
        assertTrue(r.isEmpty());
        assertEquals(ElwCircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void fiveFailures_tripsOpen() {
        for (int i = 0; i < 5; i++) {
            cb.protect(() -> { throw new RuntimeException("x"); });
        }
        assertEquals(ElwCircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void open_rejectsCallsWithoutInvokingLambda() {
        tripOpen(cb);
        AtomicInteger calls = new AtomicInteger();
        Optional<Integer> r = cb.protect(calls::incrementAndGet);
        assertTrue(r.isEmpty());
        assertEquals(0, calls.get(), "OPEN 상태에서는 람다 호출 차단");
    }

    @Test
    void afterCooldown_halfOpenProbesAndClosesOnSuccess() throws Exception {
        tripOpen(cb);
        forceOpenedAt(cb, System.currentTimeMillis() - 31_000);
        AtomicInteger calls = new AtomicInteger();
        Optional<Integer> r = cb.protect(calls::incrementAndGet);
        assertEquals(1, calls.get(), "HALF_OPEN probe 1회 호출");
        assertEquals(1, r.orElse(-1));
        assertEquals(ElwCircuitBreaker.State.CLOSED, cb.state(), "probe 성공 → CLOSED");
    }

    @Test
    void halfOpenProbeFailure_reopens() throws Exception {
        tripOpen(cb);
        forceOpenedAt(cb, System.currentTimeMillis() - 31_000);
        Optional<?> r = cb.protect(() -> { throw new RuntimeException("still down"); });
        assertTrue(r.isEmpty());
        assertEquals(ElwCircuitBreaker.State.OPEN, cb.state(), "probe 실패 → OPEN 복귀");
    }

    @Test
    void concurrentHalfOpen_onlyOneProbeExecutes() throws Exception {
        tripOpen(cb);
        forceOpenedAt(cb, System.currentTimeMillis() - 31_000);

        int n = 16;
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(n);
        AtomicInteger bodiesRun = new AtomicInteger();
        ExecutorService es = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                es.submit(() -> {
                    cb.protect(() -> {
                        if (bodiesRun.incrementAndGet() == 1) {
                            probeStarted.countDown();
                            releaseProbe.await();
                        }
                        return "ok";
                    });
                    allDone.countDown();
                });
            }
            assertTrue(probeStarted.await(3, TimeUnit.SECONDS), "첫 probe가 시작되어야 함");
            // probe가 블록된 상태에서 다른 15개 호출이 도착했을 시간 확보
            Thread.sleep(150);
            assertEquals(1, bodiesRun.get(),
                    "probe 실행 중(HALF_OPEN) 동안은 다른 호출이 람다를 실행하지 않아야 함");
            releaseProbe.countDown();
            assertTrue(allDone.await(3, TimeUnit.SECONDS));
        } finally {
            es.shutdownNow();
        }
        assertEquals(ElwCircuitBreaker.State.CLOSED, cb.state(), "probe 성공 → CLOSED 복귀");
    }

    // ----- helpers -----

    private static void tripOpen(ElwCircuitBreaker cb) {
        for (int i = 0; i < 5; i++) {
            cb.protect(() -> { throw new RuntimeException("x"); });
        }
    }

    private static void forceOpenedAt(ElwCircuitBreaker cb, long millis) throws Exception {
        Field f = ElwCircuitBreaker.class.getDeclaredField("openedAt");
        f.setAccessible(true);
        ((AtomicLong) f.get(cb)).set(millis);
    }
}

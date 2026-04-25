package com.backtesting.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bulkhead 격리 실증 — KIS 풀 포화 상태에서 DART 호출이 영향받지 않는지.
 *
 * 빅테크 운영: "한 downstream 이 느려지면 그 downstream 만 느려져야 한다"
 * 이 불변식을 자동 테스트로 고정.
 */
class BulkheadIsolationTest {

    @Test
    void kisPoolSaturation_doesNotStarveDartPool() throws Exception {
        BulkheadProperties props = new BulkheadProperties();
        props.setKis(new BulkheadProperties.Pool(2, 1));  // size=2, 큐=0 → 즉시 reject
        props.setDart(new BulkheadProperties.Pool(2, 1));
        props.setElw(new BulkheadProperties.Pool(2, 1));

        BulkheadExecutorsConfig config = new BulkheadExecutorsConfig(props);
        ExecutorService kis = config.kisExecutor();
        ExecutorService dart = config.dartExecutor();
        try {
            CountDownLatch kisHold = new CountDownLatch(1);
            AtomicInteger kisRunning = new AtomicInteger();

            // KIS 풀 2 슬롯 + 큐 1 슬롯 모두 점유
            Future<?> k1 = kis.submit(() -> blockUntil(kisRunning, kisHold));
            Future<?> k2 = kis.submit(() -> blockUntil(kisRunning, kisHold));
            Future<?> k3 = kis.submit(() -> blockUntil(kisRunning, kisHold)); // 큐 대기

            waitFor(() -> kisRunning.get() == 2, 2_000);
            assertEquals(2, kisRunning.get());

            // 풀+큐 가득 → AbortPolicy 로 reject
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> kis.submit(() -> { }));

            // DART 풀은 전혀 영향 없어야 함 — 빠르게 완료
            long start = System.currentTimeMillis();
            Future<String> dartResult = dart.submit(() -> "ok");
            assertEquals("ok", dartResult.get(500, TimeUnit.MILLISECONDS));
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).as("DART pool 격리 — KIS 포화 무관하게 빠르게 처리")
                    .isLessThan(500);

            kisHold.countDown();
            k1.get(2, TimeUnit.SECONDS);
            k2.get(2, TimeUnit.SECONDS);
            k3.get(2, TimeUnit.SECONDS);
        } finally {
            kis.shutdownNow();
            dart.shutdownNow();
        }
    }

    @Test
    void rejectionPolicy_failsFast_noCallerRuns() throws Exception {
        BulkheadProperties props = new BulkheadProperties();
        props.setKis(new BulkheadProperties.Pool(1, 1));
        props.setDart(new BulkheadProperties.Pool(1, 1));
        props.setElw(new BulkheadProperties.Pool(1, 1));

        ExecutorService kis = new BulkheadExecutorsConfig(props).kisExecutor();
        try {
            CountDownLatch hold = new CountDownLatch(1);
            AtomicInteger running = new AtomicInteger();
            Future<?> t1 = kis.submit(() -> blockUntil(running, hold));
            waitFor(() -> running.get() == 1, 2_000);
            Future<?> t2 = kis.submit(() -> { /* queue 에 대기 */ });

            // 풀 1 + 큐 1 = 2 가득 → 세 번째는 reject
            AtomicInteger callerExecuted = new AtomicInteger();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> kis.submit(() -> callerExecuted.incrementAndGet()));
            assertEquals(0, callerExecuted.get(),
                    "AbortPolicy — CallerRuns 아님. 호출 스레드가 downstream 일을 대신 하면 안 됨.");
            hold.countDown();
            t1.get(2, TimeUnit.SECONDS);
            t2.get(2, TimeUnit.SECONDS);
        } finally {
            kis.shutdownNow();
        }
    }

    private static void blockUntil(AtomicInteger running, CountDownLatch release) {
        running.incrementAndGet();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { running.decrementAndGet(); }
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}

package com.backtesting.service.kis;

import com.backtesting.config.KisProperties;
import com.backtesting.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KisRateLimiter 동시성 / 한도 검증.
 *
 * 검증 포인트:
 *   - capacity 내에서는 대기 없이 통과
 *   - 초과는 maxWaitMs 내에 슬롯 비면 대기 후 통과
 *   - 그 이상이면 KisRateLimitedException
 *   - 버킷 독립성 — general 포화가 token 대기를 유발하지 않아야 한다
 */
class KisRateLimiterTest {

    private KisProperties props;
    private MutableClock clock;
    private KisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        props = new KisProperties();
        props.setRequestsPerSecond(3);
        props.setTokenRequestsPerSecond(1);
        props.setMaxWaitMs(200);   // 테스트 빠르게
        clock = new MutableClock(Instant.parse("2026-04-24T09:00:00Z"), ZoneId.of("UTC"));
        limiter = new KisRateLimiter(props, clock);
    }

    @Test
    void acquiresUpToCapacityWithoutWaiting() {
        // 3 요청 즉시 성공.
        for (int i = 0; i < 3; i++) limiter.acquireGeneral();
        assertThat(limiter.generalWindowSize()).isEqualTo(3);
    }

    @Test
    void exceedingCapacityWithShortMaxWaitThrows() {
        // 3번 까지 성공.
        for (int i = 0; i < 3; i++) limiter.acquireGeneral();

        // clock 은 전진하지 않음 → 1초 안 지났으므로 슬롯 안 비움. maxWaitMs 200ms 만 대기 후 포기.
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> limiter.acquireGeneral())
                .isInstanceOf(KisRateLimitedException.class);
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        // 200ms 이상 기다렸는지 확인.
        assertThat(waitedMs).isGreaterThanOrEqualTo(150);
    }

    @Test
    void tokenBucketIsIndependentFromGeneral() {
        // general 버킷 포화.
        for (int i = 0; i < 3; i++) limiter.acquireGeneral();
        // token 버킷은 영향 없어야 함 — 즉시 성공.
        limiter.acquireToken();
        assertThat(limiter.tokenWindowSize()).isEqualTo(1);
    }

    @Test
    void concurrentAcquireRespectsCapacity() throws Exception {
        props.setRequestsPerSecond(5);
        props.setMaxWaitMs(50);
        limiter = new KisRateLimiter(props, clock);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    limiter.acquireGeneral();
                    allowed.incrementAndGet();
                } catch (KisRateLimitedException e) {
                    throttled.incrementAndGet();
                } catch (Exception ignored) {}
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // 정확히 5개만 즉시 허용되고 나머지는 short-timeout 으로 throttle 되어야 한다.
        assertThat(allowed.get()).isEqualTo(5);
        assertThat(throttled.get()).isEqualTo(15);
    }

    @Test
    void slotFreedAfterWindowAdvancesAllowsNextCall() {
        for (int i = 0; i < 3; i++) limiter.acquireGeneral();
        // 1초 + 여유 전진 — 윈도우 밖으로 나간 슬롯이 열린다.
        clock.advance(Duration.ofMillis(1100));
        limiter.acquireGeneral();
        // 슬라이딩 윈도우의 슬롯 수는 1.
        assertThat(limiter.generalWindowSize()).isEqualTo(1);
    }
}

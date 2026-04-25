package com.backtesting.service;

import com.backtesting.config.AlertProperties;
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
import static org.mockito.Mockito.*;

/**
 * DailyLossGuard 정확성 / 동시성 검증.
 *
 * 시나리오:
 *  1. 한도 초과 시 매수 차단, 알림 1회
 *  2. 자정 경계에서 동시 기록해도 이중 리셋 없음 (CAS)
 *  3. 수익은 한도 차감에 기여하지 않음 (음수만 손실)
 */
class DailyLossGuardTest {

    private AlertProperties props;
    private EmailService email;
    private MutableClock clock;
    private DailyLossGuard guard;

    @BeforeEach
    void setUp() {
        props = new AlertProperties();
        AlertProperties.DailyLoss loss = new AlertProperties.DailyLoss();
        loss.setLimitKrw(100_000);
        props.setDailyLoss(loss);

        email = mock(EmailService.class);
        clock = new MutableClock(Instant.parse("2026-04-24T09:00:00Z"), ZoneId.of("Asia/Seoul"));
        guard = new DailyLossGuard(props, email, clock);
    }

    @Test
    void buyAllowedByDefault() {
        assertThat(guard.isBuyAllowed()).isTrue();
        assertThat(guard.getNetRealizedKrw()).isZero();
    }

    @Test
    void exceedingLimitBlocksBuyAndAlertsOnce() {
        guard.recordRealizedPnl(-50_000);
        assertThat(guard.isBuyAllowed()).isTrue();

        guard.recordRealizedPnl(-60_000);
        assertThat(guard.isBuyAllowed()).isFalse();
        verify(email, times(1)).sendHtml(contains("한도 초과"), anyString());

        // 추가 손실 — 알림 재발 없어야 함.
        guard.recordRealizedPnl(-20_000);
        verify(email, times(1)).sendHtml(anyString(), anyString());
    }

    @Test
    void profitDoesNotAlertOrBlock() {
        guard.recordRealizedPnl(200_000);
        assertThat(guard.isBuyAllowed()).isTrue();
        verifyNoInteractions(email);
    }

    @Test
    void midnightLazilyResets() {
        guard.recordRealizedPnl(-150_000);
        assertThat(guard.isBuyAllowed()).isFalse();
        verify(email, times(1)).sendHtml(anyString(), anyString());

        // 하루 경과.
        clock.advance(Duration.ofDays(1));
        assertThat(guard.isBuyAllowed()).isTrue();
        assertThat(guard.getNetRealizedKrw()).isZero();

        // 같은 날 한 번 더 한도 초과 — 새로 알림 1회.
        guard.recordRealizedPnl(-150_000);
        verify(email, times(2)).sendHtml(anyString(), anyString());
    }

    @Test
    void concurrentRecordsAreAtomic() throws Exception {
        int threads = 32;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        guard.recordRealizedPnl(-1);
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        // 정확히 -6400 원.
        assertThat(guard.getNetRealizedKrw()).isEqualTo(-(threads * perThread));
        // 한도 -100,000 을 초과하지 않으므로 알림 없어야 한다.
        verifyNoInteractions(email);
    }

    @Test
    void concurrentAlertTriggerFiresExactlyOnce() throws Exception {
        int threads = 64;
        int perThread = 200;    // 총 -64 * 200 = -12,800 → 한도 -10만 미달
        // 한도를 낮춰 확실히 한도 초과되도록.
        props.getDailyLoss().setLimitKrw(1_000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) guard.recordRealizedPnl(-1);
                } catch (InterruptedException ignored) {}
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 알림은 정확히 1회.
        verify(email, times(1)).sendHtml(anyString(), anyString());
    }

    @Test
    void scheduledResetClearsState() {
        guard.recordRealizedPnl(-50_000);
        guard.resetDaily();
        assertThat(guard.getNetRealizedKrw()).isZero();
        assertThat(guard.isBuyAllowed()).isTrue();
    }
}

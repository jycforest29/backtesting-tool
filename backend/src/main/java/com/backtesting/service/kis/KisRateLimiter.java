package com.backtesting.service.kis;

import com.backtesting.config.KisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * KIS 초당 호출 한도 (sliding window) 제어.
 *
 * 버킷:
 *   - 일반 API (kis.requests-per-second, 실전 18 / 모의 1 / 신규 3일 3)
 *   - 토큰 발급 (kis.token-requests-per-second, KIS 고정 1)
 *
 * 재설계 포인트 (기존 대비):
 *   - 기존: synchronized + Thread.sleep — 한 스레드가 sleep 하는 동안 같은 버킷에 대기 중인
 *           다른 스레드까지 mutex 에 블록. 스레드풀 고갈 유발.
 *   - 신규: 버킷별 ReentrantLock + Condition.awaitNanos. 대기 스레드는 lock 을 풀고 park 되므로
 *           다른 버킷 / 다른 API 경로는 영향 없음.
 *
 * 계약:
 *   - acquire 는 최대 maxWaitMs 기다린 뒤에도 슬롯 확보 못하면 {@link KisRateLimitedException}.
 *   - exception 에는 추가 대기 필요 시간을 담아 클라이언트가 Retry-After 헤더로 노출 가능.
 *   - 버킷 내부 시계는 주입된 {@link Clock} — 테스트에서 시간 전진 가능.
 */
@Slf4j
@Component
public class KisRateLimiter {

    private final KisProperties props;
    private final Clock clock;

    private final Bucket general;
    private final Bucket token;

    public KisRateLimiter(KisProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.general = new Bucket("general", clock);
        this.token = new Bucket("token", clock);
    }

    public void acquireGeneral() {
        general.acquire(props.getRequestsPerSecond(), props.getMaxWaitMs());
    }

    public void acquireToken() {
        token.acquire(props.getTokenRequestsPerSecond(), props.getMaxWaitMs());
    }

    // 테스트용 훅.
    int generalWindowSize() { return general.size(); }
    int tokenWindowSize()   { return token.size(); }

    /**
     * 1초 슬라이딩 윈도우 + 버킷별 독립 lock. fair=true 로 FIFO 공정성 보장.
     */
    private static final class Bucket {
        private final String tag;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition slotAvailable = lock.newCondition();
        private final Deque<Long> window = new ArrayDeque<>();

        Bucket(String tag, Clock clock) {
            this.tag = tag;
            this.clock = clock;
        }

        void acquire(int capacity, long maxWaitMs) {
            if (capacity <= 0) {
                throw new IllegalStateException("Rate limit capacity must be > 0: bucket=" + tag);
            }
            long deadlineNanos = System.nanoTime() + Duration.ofMillis(maxWaitMs).toNanos();

            lock.lock();
            try {
                while (true) {
                    long now = clock.millis();
                    trimOlderThan(now - 1000);
                    if (window.size() < capacity) {
                        window.offerLast(now);
                        // 다음 대기자 깨우기 (여러 슬롯 동시 비워진 경우).
                        slotAvailable.signal();
                        return;
                    }
                    long oldest = window.peekFirst();
                    long waitMs = 1000 - (now - oldest) + 5; // 5ms 여유
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        log.warn("Rate limit exceeded ({}) — would need to wait {}ms, exceeds maxWait", tag, waitMs);
                        throw new KisRateLimitedException(waitMs);
                    }
                    long parkNanos = Math.min(remainingNanos, Duration.ofMillis(waitMs).toNanos());
                    // parkNanos 동안 lock 해제하며 대기. interrupt 시 InterruptedException.
                    try {
                        slotAvailable.awaitNanos(parkNanos);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new KisRateLimitedException(waitMs);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        void trimOlderThan(long windowStart) {
            while (!window.isEmpty() && window.peekFirst() < windowStart) {
                window.pollFirst();
            }
        }

        int size() {
            lock.lock();
            try { return window.size(); } finally { lock.unlock(); }
        }
    }
}

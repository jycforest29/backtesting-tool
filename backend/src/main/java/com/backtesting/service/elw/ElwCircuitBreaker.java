package com.backtesting.service.elw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KIS ELW 엔드포인트 전용 서킷 브레이커.
 * 슬라이딩 윈도우 실패 카운트 + half-open 단일 탐색.
 *
 * 상태 전이:
 *   CLOSED    → OPEN       windowMs 내 실패 >= threshold
 *   OPEN      → HALF_OPEN  마지막 실패로부터 cooldownMs 경과
 *   HALF_OPEN → CLOSED     탐색 성공
 *   HALF_OPEN → OPEN       탐색 실패 (쿨다운 재시작)
 *
 * 동시성: CAS 기반 단일 탐색 보장. 여러 스레드가 동시에 OPEN→HALF_OPEN 시도 시 1개만 통과.
 */
@Slf4j
@Component
public class ElwCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final long WINDOW_MS = 60_000;
    private static final int THRESHOLD = 5;
    private static final long COOLDOWN_MS = 30_000;

    private final Clock clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong firstFailureAt = new AtomicLong(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    @Autowired
    public ElwCircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    /** 테스트/기존 호출자 호환용 — 기본 system clock. */
    public ElwCircuitBreaker() {
        this(Clock.systemDefaultZone());
    }

    public State state() { return state.get(); }

    /** 테스트 전용 — 쿨다운 경과 시뮬레이트 시 openedAt 을 과거로 설정. Clock 주입 선호. */
    void setOpenedAtForTest(long epochMs) { openedAt.set(epochMs); }

    public <T> Optional<T> protect(Callable<T> call) {
        while (true) {
            State s = state.get();
            if (s == State.CLOSED) {
                return runCall(call);
            }
            if (s == State.HALF_OPEN) {
                // 다른 스레드가 이미 probe 실행 중 — 같이 람다를 실행하면 probe 의미가 사라짐
                return Optional.empty();
            }
            // OPEN
            if (clock.millis() - openedAt.get() < COOLDOWN_MS) {
                return Optional.empty();
            }
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                log.info("ElwCircuitBreaker: OPEN → HALF_OPEN (probing)");
                return runCall(call);
            }
            // CAS 패배 → 다른 스레드가 먼저 전환, 상태 재조회
        }
    }

    private <T> Optional<T> runCall(Callable<T> call) {
        try {
            T result = call.call();
            onSuccess();
            return Optional.ofNullable(result);
        } catch (Exception e) {
            onFailure(e);
            return Optional.empty();
        }
    }

    private void onSuccess() {
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            failureCount.set(0);
            firstFailureAt.set(0);
            log.info("ElwCircuitBreaker: HALF_OPEN → CLOSED (probe ok)");
        }
    }

    private void onFailure(Exception e) {
        if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            openedAt.set(clock.millis());
            log.warn("ElwCircuitBreaker: HALF_OPEN → OPEN (probe failed: {})", e.getMessage());
            return;
        }
        long now = clock.millis();
        long first = firstFailureAt.get();
        if (first == 0 || now - first > WINDOW_MS) {
            firstFailureAt.set(now);
            failureCount.set(1);
        } else {
            failureCount.incrementAndGet();
        }
        if (failureCount.get() >= THRESHOLD && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAt.set(now);
            log.warn("ElwCircuitBreaker: CLOSED → OPEN ({} failures in {}ms)", THRESHOLD, WINDOW_MS);
        }
    }
}

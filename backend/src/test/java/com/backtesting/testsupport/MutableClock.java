package com.backtesting.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트용 수동 전진 clock. DST 에서 tick-by-tick 시간 제어 수단.
 * 스레드 안전: synchronized 로 now 갱신/조회.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("Asia/Seoul"));
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public synchronized void advance(Duration d) { now = now.plus(d); }
    public synchronized void advanceMs(long ms) { now = now.plusMillis(ms); }
    public synchronized void set(Instant t) { now = t; }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(now, z); }
    @Override public synchronized Instant instant() { return now; }
    @Override public synchronized long millis() { return now.toEpochMilli(); }
}

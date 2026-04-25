package com.backtesting.common;

import java.time.Instant;
import java.util.UUID;

/**
 * 요청 수준 컨텍스트 — deadline + traceId.
 *
 * ThreadLocal 기반. async 경계를 넘길 때는 반드시 copy → 자식 스레드 set → 종료 시 clear.
 * Bulkhead executor 들이 ContextPropagatingTaskDecorator 를 통해 자동 전파.
 *
 * remainingMs() == 0 은 "즉시 만료" 가 아니라 "여유 0ms" 로 해석 — downstream 은
 * isExpired() 로 판단 후 DeadlineExceededException.
 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> TL = new ThreadLocal<>();
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final Instant deadline;
    private final String traceId;

    private RequestContext(Instant deadline, String traceId) {
        this.deadline = deadline;
        this.traceId = traceId;
    }

    public static RequestContext withTimeout(long timeoutMs, String traceId) {
        return new RequestContext(
                Instant.now().plusMillis(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS),
                traceId != null ? traceId : newTraceId());
    }

    public static RequestContext withDeadline(Instant deadline, String traceId) {
        return new RequestContext(deadline, traceId != null ? traceId : newTraceId());
    }

    public static void set(RequestContext ctx) { TL.set(ctx); }
    public static RequestContext current() { return TL.get(); }
    public static void clear() { TL.remove(); }

    public Instant deadline() { return deadline; }
    public String traceId() { return traceId; }

    public long remainingMs() {
        long r = deadline.toEpochMilli() - System.currentTimeMillis();
        return Math.max(0, r);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(deadline);
    }

    /** 현재 스레드에 context 가 있으면 remainingMs, 없으면 defaultMs. downstream HTTP timeout 계산용. */
    public static long effectiveTimeoutMs(long defaultMs) {
        RequestContext c = TL.get();
        if (c == null) return defaultMs;
        long rem = c.remainingMs();
        return rem > 0 ? Math.min(defaultMs, rem) : 0;
    }

    public static void requireNotExpired() {
        RequestContext c = TL.get();
        if (c != null && c.isExpired()) {
            throw new DeadlineExceededException(
                    "request deadline exceeded (traceId=" + c.traceId() + ")");
        }
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 16);
    }

    public static final class DeadlineExceededException extends RuntimeException {
        public DeadlineExceededException(String m) { super(m); }
    }
}

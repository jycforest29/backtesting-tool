package com.backtesting.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextTest {

    @AfterEach
    void cleanup() { RequestContext.clear(); }

    @Test
    void remainingMs_decreasesOverTime() {
        RequestContext c = RequestContext.withTimeout(10_000, "t1");
        long first = c.remainingMs();
        sleep(20);
        long second = c.remainingMs();
        assertTrue(second < first);
        assertTrue(second > 0);
    }

    @Test
    void expired_whenDeadlinePassed() throws Exception {
        RequestContext c = RequestContext.withDeadline(Instant.now().minusSeconds(1), "t2");
        assertTrue(c.isExpired());
        assertEquals(0, c.remainingMs(), "만료된 컨텍스트의 remainingMs 는 0");
    }

    @Test
    void requireNotExpired_throwsWhenExpired() {
        RequestContext.set(RequestContext.withDeadline(Instant.now().minusSeconds(1), "t"));
        assertThrows(RequestContext.DeadlineExceededException.class,
                RequestContext::requireNotExpired);
    }

    @Test
    void requireNotExpired_noopWhenNoContext() {
        RequestContext.clear();
        assertDoesNotThrow(RequestContext::requireNotExpired);
    }

    @Test
    void effectiveTimeoutMs_capsAtRemaining() {
        RequestContext.set(RequestContext.withTimeout(500, "t"));
        long cap = RequestContext.effectiveTimeoutMs(10_000);
        assertTrue(cap <= 500, "remainingMs=500 이면 10s 요청도 500 이하로 캡");
        assertTrue(cap > 0);
    }

    @Test
    void effectiveTimeoutMs_fallsBackWithoutContext() {
        RequestContext.clear();
        assertEquals(5000, RequestContext.effectiveTimeoutMs(5000));
    }

    @Test
    void threadLocal_isolation() throws InterruptedException {
        RequestContext.set(RequestContext.withTimeout(10_000, "main"));
        Thread t = new Thread(() -> {
            assertNull(RequestContext.current(), "자식 스레드엔 자동 전파되지 않음");
        });
        t.start();
        t.join();
        assertNotNull(RequestContext.current());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

package com.backtesting.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestContext + MDC 가 executor 경계를 넘어 전파되는지.
 * Bulkhead pool 이 자식 스레드로 deadline/traceId 를 들고 가야 downstream 이 남은 예산을
 * 올바르게 계산할 수 있다.
 */
class ContextPropagatingExecutorTest {

    @AfterEach
    void cleanup() {
        RequestContext.clear();
        MDC.clear();
    }

    @Test
    void submitRunnable_inheritsRequestContext() throws Exception {
        RequestContext parent = RequestContext.withTimeout(10_000, "parent-trace");
        RequestContext.set(parent);
        MDC.put("traceId", "parent-trace");

        ExecutorService es = new ContextPropagatingExecutor(Executors.newSingleThreadExecutor());
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenMdc = new AtomicReference<>();

        es.submit(() -> {
            RequestContext c = RequestContext.current();
            seenTrace.set(c == null ? null : c.traceId());
            seenMdc.set(MDC.get("traceId"));
        }).get(2, TimeUnit.SECONDS);
        es.shutdown();

        assertEquals("parent-trace", seenTrace.get());
        assertEquals("parent-trace", seenMdc.get());
    }

    @Test
    void submitCallable_inheritsAndReturns() throws Exception {
        RequestContext.set(RequestContext.withTimeout(5000, "abc"));
        ExecutorService es = new ContextPropagatingExecutor(Executors.newSingleThreadExecutor());
        Future<String> f = es.submit(() -> RequestContext.current().traceId());
        assertEquals("abc", f.get(2, TimeUnit.SECONDS));
        es.shutdown();
    }

    @Test
    void childThread_clearsAfterRun_noLeak() throws Exception {
        RequestContext.set(RequestContext.withTimeout(5000, "leaktest"));
        ExecutorService single = Executors.newSingleThreadExecutor();
        ExecutorService es = new ContextPropagatingExecutor(single);

        es.submit(() -> { /* first task */ }).get(2, TimeUnit.SECONDS);

        // 다음 task 를 context 없이 제출 (부모에서 clear 한 후)
        RequestContext.clear();
        AtomicReference<RequestContext> leaked = new AtomicReference<>();
        es.submit(() -> leaked.set(RequestContext.current())).get(2, TimeUnit.SECONDS);
        es.shutdown();
        assertNull(leaked.get(), "이전 task 컨텍스트가 다음 task 에 남으면 안 됨");
    }

    @Test
    void noContextInParent_childGetsNull() throws Exception {
        RequestContext.clear();
        ExecutorService es = new ContextPropagatingExecutor(Executors.newSingleThreadExecutor());
        Future<RequestContext> f = es.submit(RequestContext::current);
        assertNull(f.get(2, TimeUnit.SECONDS));
        es.shutdown();
    }
}

package com.backtesting.common;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.*;

/**
 * ExecutorService 데코레이터 — 제출 시점의 컨텍스트를 자식 스레드로 복사.
 *
 * 복사 대상:
 *  - RequestContext (deadline, traceId)
 *  - SLF4J MDC (로그 correlation)
 *  - io.opentelemetry.context.Context (trace/span parent) — 자식 span 을 부모에 연결
 *
 * CompletableFuture.supplyAsync(..., executor) 든 executor.submit(...) 이든 동일.
 */
public final class ContextPropagatingExecutor implements ExecutorService {

    private final ExecutorService delegate;

    public ContextPropagatingExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    private Runnable wrap(Runnable r) {
        RequestContext ctx = RequestContext.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Context otel = Context.current();
        return () -> runWith(ctx, mdc, otel, r);
    }

    private <T> Callable<T> wrap(Callable<T> c) {
        RequestContext ctx = RequestContext.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Context otel = Context.current();
        return () -> callWith(ctx, mdc, otel, c);
    }

    private static void runWith(RequestContext ctx, Map<String, String> mdc, Context otel, Runnable r) {
        if (ctx != null) RequestContext.set(ctx);
        if (mdc != null) MDC.setContextMap(mdc);
        try (Scope ignored = otel.makeCurrent()) {
            r.run();
        } finally {
            RequestContext.clear();
            MDC.clear();
        }
    }

    private static <T> T callWith(RequestContext ctx, Map<String, String> mdc, Context otel, Callable<T> c) throws Exception {
        if (ctx != null) RequestContext.set(ctx);
        if (mdc != null) MDC.setContextMap(mdc);
        try (Scope ignored = otel.makeCurrent()) {
            return c.call();
        } finally {
            RequestContext.clear();
            MDC.clear();
        }
    }

    // ----- ExecutorService delegations -----

    @Override public void execute(Runnable command) { delegate.execute(wrap(command)); }
    @Override public Future<?> submit(Runnable task) { return delegate.submit(wrap(task)); }
    @Override public <T> Future<T> submit(Runnable task, T result) { return delegate.submit(wrap(task), result); }
    @Override public <T> Future<T> submit(Callable<T> task) { return delegate.submit(wrap(task)); }
    @Override public void shutdown() { delegate.shutdown(); }
    @Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
        return delegate.awaitTermination(t, u);
    }
    @Override public <T> java.util.List<Future<T>> invokeAll(
            java.util.Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(this::wrap).toList());
    }
    @Override public <T> java.util.List<Future<T>> invokeAll(
            java.util.Collection<? extends Callable<T>> tasks, long t, TimeUnit u) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(this::wrap).toList(), t, u);
    }
    @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream().map(this::wrap).toList());
    }
    @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks, long t, TimeUnit u)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream().map(this::wrap).toList(), t, u);
    }
}

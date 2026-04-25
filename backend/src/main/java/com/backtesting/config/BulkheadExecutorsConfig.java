package com.backtesting.config;

import com.backtesting.common.ContextPropagatingExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downstream 별 bulkhead executor.
 *
 * 각 풀은 RejectedExecutionHandler=AbortPolicy — 큐가 가득 차면 CallerRuns 대신 빠르게 Reject 해서
 * 호출자가 downstream 과열 사실을 즉시 인지하도록. AbortPolicy 는 RejectedExecutionException 을
 * 던지므로 상위에서 circuit breaker 또는 fallback 으로 처리.
 */
@Configuration
@RequiredArgsConstructor
public class BulkheadExecutorsConfig {

    private final BulkheadProperties props;

    @Bean(name = "kisExecutor", destroyMethod = "shutdown")
    public ExecutorService kisExecutor() {
        return build("kis", props.getKis());
    }

    @Bean(name = "dartExecutor", destroyMethod = "shutdown")
    public ExecutorService dartExecutor() {
        return build("dart", props.getDart());
    }

    @Bean(name = "elwExecutor", destroyMethod = "shutdown")
    public ExecutorService elwExecutor() {
        return build("elw", props.getElw());
    }

    private ExecutorService build(String tag, BulkheadProperties.Pool cfg) {
        ThreadFactory tf = new NamedDaemonThreadFactory(tag);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                cfg.getSize(), cfg.getSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(cfg.getQueueCapacity()),
                tf,
                new ThreadPoolExecutor.AbortPolicy()
        );
        tpe.allowCoreThreadTimeOut(false);
        return new ContextPropagatingExecutor(tpe);
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedDaemonThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-bh-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}

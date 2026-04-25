package com.backtesting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Downstream 별 격리된 thread pool 설정.
 * 한 downstream 이 느려져도 다른 downstream 호출이 고갈되지 않도록 분리.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "bulkhead")
public class BulkheadProperties {
    private Pool kis = new Pool(8, 32);
    private Pool dart = new Pool(4, 16);
    private Pool elw = new Pool(8, 32);

    @Data
    public static class Pool {
        private int size;
        private int queueCapacity;

        public Pool() {}
        public Pool(int size, int queueCapacity) {
            this.size = size;
            this.queueCapacity = queueCapacity;
        }
    }
}

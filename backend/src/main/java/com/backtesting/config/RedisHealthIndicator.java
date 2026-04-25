package com.backtesting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis 연결 상태 체크.
 *
 * 부재 허용 설계 — Redis 미가동 시 애플리케이션은 degraded (cache disabled) 로 동작.
 * 따라서 DOWN 대신 OUT_OF_SERVICE 로 표현 — K8s 프로브가 restart 를 유발하지 않도록.
 *
 * RedisAutoConfiguration 이 exclude 된 환경 (테스트/CI) 에서도 컨텍스트가 깨지지 않도록
 * KafkaHealthIndicator 와 동일한 ObjectProvider 패턴을 사용 — 빈이 없을 때 OUT_OF_SERVICE 보고.
 */
@Slf4j
@Component("redisHealth")
@ConditionalOnClass(RedisConnectionFactory.class)
public class RedisHealthIndicator implements HealthIndicator {

    private final ObjectProvider<RedisConnectionFactory> factoryProvider;

    public RedisHealthIndicator(ObjectProvider<RedisConnectionFactory> factoryProvider) {
        this.factoryProvider = factoryProvider;
    }

    @Override
    public Health health() {
        RedisConnectionFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            return Health.status("OUT_OF_SERVICE")
                    .withDetail("reason", "RedisConnectionFactory bean 없음 (autoconfig 비활성)")
                    .build();
        }
        Instant start = Instant.now();
        try (RedisConnection conn = factory.getConnection()) {
            String pong = new String(conn.ping().getBytes());
            Duration rtt = Duration.between(start, Instant.now());
            return Health.up()
                    .withDetail("ping", pong)
                    .withDetail("roundTripMs", rtt.toMillis())
                    .build();
        } catch (Exception e) {
            return Health.status("OUT_OF_SERVICE")
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .withDetail("hint", "cache/distributed-lock 기능이 동작 안 할 수 있음")
                    .build();
        }
    }
}

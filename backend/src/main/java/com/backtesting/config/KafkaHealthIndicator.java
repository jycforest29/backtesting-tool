package com.backtesting.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Kafka cluster 연결 상태 체크.
 *
 * Kafka 미가동 시 outbox publisher 가 지수백오프로 재시도하므로 DOWN 대신 OUT_OF_SERVICE.
 * K8s readiness 는 해당 상태를 "not ready" 로 인식하도록 management.endpoint.health.status-order 튜닝 가능.
 */
@Slf4j
@Component("kafkaHealth")
@ConditionalOnClass(KafkaAdmin.class)
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final ObjectProvider<KafkaAdmin> kafkaAdminProvider;

    public KafkaHealthIndicator(ObjectProvider<KafkaAdmin> kafkaAdminProvider) {
        this.kafkaAdminProvider = kafkaAdminProvider;
    }

    @Override
    public Health health() {
        KafkaAdmin admin = kafkaAdminProvider.getIfAvailable();
        if (admin == null) {
            return Health.status("OUT_OF_SERVICE")
                    .withDetail("reason", "KafkaAdmin bean 없음 (autoconfig 비활성)")
                    .build();
        }
        Instant start = Instant.now();
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            DescribeClusterResult cluster = client.describeCluster();
            String clusterId = cluster.clusterId().get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            int nodes = cluster.nodes().get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).size();
            Duration rtt = Duration.between(start, Instant.now());
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodeCount", nodes)
                    .withDetail("roundTripMs", rtt.toMillis())
                    .build();
        } catch (Exception e) {
            return Health.status("OUT_OF_SERVICE")
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .withDetail("hint", "outbox 이벤트 발행 지연 가능 (재시도 중)")
                    .build();
        }
    }
}

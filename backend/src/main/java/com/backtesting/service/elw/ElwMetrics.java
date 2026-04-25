package com.backtesting.service.elw;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ELW 파이프라인 메트릭 (Prometheus).
 * 서킷 브레이커·IV 솔버·스캔 결과를 /actuator/prometheus 로 노출.
 * ElwCircuitBreaker 도메인 클래스에 Micrometer 의존을 섞지 않기 위해 분리.
 */
@Component
@RequiredArgsConstructor
public class ElwMetrics {

    private final MeterRegistry registry;
    private final ElwCircuitBreaker breaker;

    @PostConstruct
    void init() {
        Gauge.builder("elw.circuit.state", breaker, ElwMetrics::stateNumber)
                .description("ELW circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN)")
                .register(registry);
    }

    /** 개별 IV 계산 결과 카운트 (status tag 로 구분: CONVERGED / MAX_ITER / NO_SOLUTION / ...). */
    public void recordIvOutcome(String status) {
        Counter.builder("elw.iv.outcome")
                .tag("status", status)
                .description("IV solver outcome count by status")
                .register(registry)
                .increment();
    }

    /** 스캔 전체 소요시간 + outcome tag (ok / degraded). */
    public Timer scanTimer(String outcome) {
        return Timer.builder("elw.scan.duration")
                .tag("outcome", outcome)
                .description("ELW skew scan duration")
                .register(registry);
    }

    /** degraded 응답 사유별 카운트. */
    public void recordDegraded(String reason) {
        Counter.builder("elw.scan.degraded")
                .tag("reason", classifyReason(reason))
                .description("Degraded ELW scan responses by cause")
                .register(registry)
                .increment();
    }

    private static double stateNumber(ElwCircuitBreaker cb) {
        return switch (cb.state()) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }

    /** Prometheus cardinality 관리를 위해 degradedReason 문자열을 고정 카테고리로 압축. */
    private static String classifyReason(String reason) {
        if (reason == null) return "unknown";
        if (reason.contains("미구성")) return "not_configured";
        if (reason.contains("circuit")) return "circuit_open";
        if (reason.contains("비어")) return "empty_chain";
        if (reason.contains("기초자산")) return "underlying_fetch_failed";
        return "other";
    }
}

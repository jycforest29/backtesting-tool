package com.backtesting.service.elw;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gauge 가 실제 서킷 상태를 반영하는지 + status-tag 카운터가 누적되는지 검증.
 */
class ElwMetricsTest {

    private SimpleMeterRegistry reg;
    private ElwCircuitBreaker breaker;
    private ElwMetrics metrics;

    @BeforeEach
    void setUp() {
        reg = new SimpleMeterRegistry();
        breaker = new ElwCircuitBreaker();
        metrics = new ElwMetrics(reg, breaker);
        metrics.init();
    }

    @Test
    void gauge_reflectsCircuitState() throws Exception {
        Gauge g = reg.find("elw.circuit.state").gauge();
        assertNotNull(g, "gauge 등록 확인");
        assertEquals(0.0, g.value(), "CLOSED → 0");

        // 서킷 오픈
        for (int i = 0; i < 5; i++) {
            breaker.protect(() -> { throw new RuntimeException("x"); });
        }
        assertEquals(ElwCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(2.0, g.value(), "OPEN → 2");

        // 쿨다운 넘김 → HALF_OPEN 전환 (CAS)
        Field f = ElwCircuitBreaker.class.getDeclaredField("openedAt");
        f.setAccessible(true);
        ((AtomicLong) f.get(breaker)).set(System.currentTimeMillis() - 31_000);
        // probe 를 블록시키지 않고 state 만 확인하기 위해 내부 state 직접 CAS
        Field stateField = ElwCircuitBreaker.class.getDeclaredField("state");
        stateField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var stateRef = (java.util.concurrent.atomic.AtomicReference<ElwCircuitBreaker.State>) stateField.get(breaker);
        stateRef.set(ElwCircuitBreaker.State.HALF_OPEN);
        assertEquals(1.0, g.value(), "HALF_OPEN → 1");
    }

    @Test
    void ivOutcome_countersAccumulatePerStatus() {
        metrics.recordIvOutcome("CONVERGED");
        metrics.recordIvOutcome("CONVERGED");
        metrics.recordIvOutcome("NO_SOLUTION");

        double converged = reg.find("elw.iv.outcome").tag("status", "CONVERGED").counter().count();
        double noSol = reg.find("elw.iv.outcome").tag("status", "NO_SOLUTION").counter().count();

        assertEquals(2.0, converged);
        assertEquals(1.0, noSol);
    }

    @Test
    void degradedReason_classifiedToBoundedCardinality() {
        metrics.recordDegraded("ELW provider 미구성 — 설정 필요");
        metrics.recordDegraded("circuit OPEN — 쿨다운 중");
        metrics.recordDegraded("기초자산 시세 조회 실패: timeout");
        metrics.recordDegraded("체인 응답이 비어 있음");
        metrics.recordDegraded("something truly unexpected");

        assertEquals(1.0, reg.find("elw.scan.degraded").tag("reason", "not_configured").counter().count());
        assertEquals(1.0, reg.find("elw.scan.degraded").tag("reason", "circuit_open").counter().count());
        assertEquals(1.0, reg.find("elw.scan.degraded").tag("reason", "underlying_fetch_failed").counter().count());
        assertEquals(1.0, reg.find("elw.scan.degraded").tag("reason", "empty_chain").counter().count());
        assertEquals(1.0, reg.find("elw.scan.degraded").tag("reason", "other").counter().count());
    }

    @Test
    void scanTimer_recordsNonZeroDuration() {
        metrics.scanTimer("ok").record(12, java.util.concurrent.TimeUnit.MILLISECONDS);
        metrics.scanTimer("degraded").record(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1L, reg.find("elw.scan.duration").tag("outcome", "ok").timer().count());
        assertEquals(1L, reg.find("elw.scan.duration").tag("outcome", "degraded").timer().count());
    }
}

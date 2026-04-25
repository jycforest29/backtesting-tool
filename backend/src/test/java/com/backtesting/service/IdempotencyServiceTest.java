package com.backtesting.service;

import com.backtesting.persistence.IdempotencyRecordEntity;
import com.backtesting.persistence.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IdempotencyService 핵심 시나리오:
 *  - 신규 키: 실행 + COMPLETED 기록
 *  - 같은 키 + 같은 request body: 캐시 응답 (replay=true)
 *  - 같은 키 + 다른 request body: 409 Conflict
 *  - 같은 키 + IN_FLIGHT: 409 InFlight
 *  - operation 중 예외: FAILED 기록 + 예외 전파
 */
class IdempotencyServiceTest {

    private IdempotencyRecordRepository repo;
    private IdempotencyService svc;

    @BeforeEach
    void setUp() {
        repo = mock(IdempotencyRecordRepository.class);
        svc = new IdempotencyService(repo, Clock.systemDefaultZone());
    }

    @Test
    void newKey_executesAndMarksCompleted() {
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByKey("k1")).thenReturn(Optional.of(inFlight("k1", "hash-of-req")));

        IdempotencyService.Result<String> r = svc.execute("k1", new Req("buy", 10), String.class,
                () -> "ORDER123");

        assertEquals("ORDER123", r.value());
        assertFalse(r.replay());
        verify(repo, atLeastOnce()).save(argThat(e -> "COMPLETED".equals(e.getStatus())));
    }

    @Test
    void sameKey_sameBody_returnsCachedReplay() throws Exception {
        Req req = new Req("buy", 10);
        String hash = hashOf(req);

        IdempotencyRecordEntity completed = new IdempotencyRecordEntity();
        completed.setKey("k2");
        completed.setRequestHash(hash);
        completed.setStatus("COMPLETED");
        completed.setResponsePayload("\"CACHED_ORDER\"");

        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repo.findByKey("k2")).thenReturn(Optional.of(completed));

        IdempotencyService.Result<String> r = svc.execute("k2", req, String.class,
                () -> fail("operation must not be invoked on replay"));

        assertEquals("CACHED_ORDER", r.value());
        assertTrue(r.replay());
    }

    @Test
    void sameKey_differentBody_throwsConflict() {
        IdempotencyRecordEntity existing = new IdempotencyRecordEntity();
        existing.setKey("k3");
        existing.setRequestHash("different-hash");
        existing.setStatus("COMPLETED");

        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repo.findByKey("k3")).thenReturn(Optional.of(existing));

        assertThrows(IdempotencyService.Conflict.class,
                () -> svc.execute("k3", new Req("sell", 5), String.class, () -> "x"));
    }

    @Test
    void sameKey_inFlight_throwsInFlight() {
        IdempotencyRecordEntity inFlight = new IdempotencyRecordEntity();
        inFlight.setKey("k4");
        // IN_FLIGHT 이므로 hash 상관없이 InFlight 가 우선...
        // 실제 구현은 hash 먼저 체크. 일치시켜 IN_FLIGHT 경로로.
        Req req = new Req("buy", 1);
        try {
            inFlight.setRequestHash(hashOf(req));
        } catch (Exception e) { fail(e); }
        inFlight.setStatus("IN_FLIGHT");

        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repo.findByKey("k4")).thenReturn(Optional.of(inFlight));

        assertThrows(IdempotencyService.InFlight.class,
                () -> svc.execute("k4", req, String.class, () -> "x"));
    }

    @Test
    void operationException_marksFailedAndPropagates() {
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByKey("k5")).thenReturn(Optional.of(inFlight("k5", "h")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> svc.execute("k5", new Req("buy", 1), String.class,
                        () -> { throw new RuntimeException("KIS rejected"); }));

        assertEquals("KIS rejected", thrown.getMessage());
        verify(repo, atLeastOnce()).save(argThat(e -> "FAILED".equals(e.getStatus())));
    }

    @Test
    void newKeyRunsOperation_exactlyOnce() {
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByKey("k6")).thenReturn(Optional.of(inFlight("k6", "h")));
        AtomicInteger calls = new AtomicInteger();

        svc.execute("k6", new Req("x", 0), String.class, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals(1, calls.get());
    }

    // ----- helpers -----

    private static IdempotencyRecordEntity inFlight(String key, String hash) {
        IdempotencyRecordEntity e = new IdempotencyRecordEntity();
        e.setKey(key);
        e.setRequestHash(hash);
        e.setStatus("IN_FLIGHT");
        e.setCreatedAt(Instant.now());
        return e;
    }

    private static String hashOf(Object o) throws Exception {
        String json = new ObjectMapper().writeValueAsString(o);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(md.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    record Req(String side, int qty) {}
}

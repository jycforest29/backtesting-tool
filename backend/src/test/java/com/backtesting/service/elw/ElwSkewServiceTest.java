package com.backtesting.service.elw;

import com.backtesting.config.ElwProperties;
import com.backtesting.model.elw.ElwModels.ElwContract;
import com.backtesting.model.elw.ElwModels.ElwSkewResponse;
import com.backtesting.service.kis.KisMarketDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 장애 시나리오별 degraded 응답 검증. 정상 경로는 KIS 실제 엔드포인트 의존이라 별도 integration.
 */
class ElwSkewServiceTest {

    @Test
    void providerUnavailable_returnsDegraded() {
        ElwSkewService svc = build(new StubProvider(false, List.of()));
        ElwSkewResponse r = svc.scan("005930");

        assertNotNull(r.degradedReason());
        assertTrue(r.degradedReason().contains("미구성"));
        assertEquals(0, r.contractsFetched());
        assertTrue(r.rows().isEmpty());
        assertEquals("CLOSED", r.circuitState());
    }

    @Test
    void emptyChain_returnsDegraded() {
        ElwSkewService svc = build(new StubProvider(true, List.of()));
        ElwSkewResponse r = svc.scan("005930");

        assertNotNull(r.degradedReason());
        assertTrue(r.degradedReason().contains("비어"));
    }

    @Test
    void providerThrows_circuitAbsorbs_degradedResponse() {
        ElwSkewService svc = build(new ThrowingProvider());
        ElwSkewResponse r = svc.scan("005930");

        assertNotNull(r.degradedReason());
        assertTrue(r.rows().isEmpty());
        // 1회 실패로는 아직 OPEN 아님 — 브레이커는 degraded 를 empty Optional 로 리턴
        assertEquals("CLOSED", r.circuitState());
    }

    // ----- helpers -----

    private ElwSkewService build(ElwChainProvider provider) {
        ElwProperties props = new ElwProperties();
        props.setConcurrency(2);
        props.setIvTimeoutMs(2000);
        ElwSkewService svc = new ElwSkewService(
                props, provider, new ElwCircuitBreaker(), mock(KisMarketDataService.class));
        svc.init();
        return svc;
    }

    private record StubProvider(boolean available, List<ElwContract> chain) implements ElwChainProvider {
        @Override public List<ElwContract> fetchChain(String u) { return chain; }
        @Override public boolean isAvailable() { return available; }
        @Override public String label() { return "stub"; }
    }

    private static class ThrowingProvider implements ElwChainProvider {
        @Override public List<ElwContract> fetchChain(String u) { throw new RuntimeException("network down"); }
        @Override public boolean isAvailable() { return true; }
        @Override public String label() { return "throwing-stub"; }
    }
}

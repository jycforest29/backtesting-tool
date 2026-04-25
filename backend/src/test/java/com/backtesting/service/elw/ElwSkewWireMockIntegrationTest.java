package com.backtesting.service.elw;

import com.backtesting.config.ElwProperties;
import com.backtesting.config.KisProperties;
import com.backtesting.model.PricePoint;
import com.backtesting.model.elw.ElwModels.ElwSkewResponse;
import com.backtesting.service.kis.KisAuthService;
import com.backtesting.service.kis.KisHttpCaller;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.KisRateLimiter;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ELW 스캐너 end-to-end (HTTP 스텁 기반).
 * 서킷 브레이커 / degraded 전파 / 정상 경로 — 실제 HTTP 왕복 포함.
 */
class ElwSkewWireMockIntegrationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void endToEnd_happyPath_computesSkew() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(okJson(chainWithCallPutPair())));

        ElwSkewService svc = buildService(fakeUnderlyingAt(2500));
        ElwSkewResponse r = svc.scan("005930");

        assertNull(r.degradedReason());
        assertEquals(2, r.contractsFetched());
        assertEquals("CLOSED", r.circuitState());
        assertEquals(2, r.rows().size(),
                "콜·풋 각 1행 IV 계산 (수렴 or 실패 무관하게 행은 유지)");
        assertEquals(1, r.skew().size(), "동일 만기·행사가 쌍 → skew point 1개");
    }

    @Test
    void fiveConsecutive500s_tripBreaker_subsequentScansDegraded() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(serverError()));

        ElwSkewService svc = buildService(fakeUnderlyingAt(2500));

        for (int i = 0; i < 5; i++) {
            ElwSkewResponse r = svc.scan("005930");
            assertNotNull(r.degradedReason());
        }
        // 5회 실패 직후: 다음 scan은 "circuit OPEN — 스킵" 응답
        ElwSkewResponse r = svc.scan("005930");
        assertEquals("OPEN", r.circuitState());
        assertTrue(r.degradedReason().contains("circuit"),
                "서킷 OPEN 후에는 실제 HTTP 호출 없이 즉시 degraded 응답");
    }

    @Test
    void breakerRecovers_onProbeSuccess() throws Exception {
        // 먼저 500으로 서킷 트립
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(serverError()));
        ElwSkewService svc = buildService(fakeUnderlyingAt(2500));
        for (int i = 0; i < 5; i++) svc.scan("005930");
        assertEquals("OPEN", svc.scan("005930").circuitState());

        // 스텁을 정상으로 교체
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(okJson(chainWithCallPutPair())));

        // 쿨다운 경과 시뮬레이트
        forceCooldownElapsed(svc);

        ElwSkewResponse r = svc.scan("005930");
        assertNull(r.degradedReason(), "probe 성공 → 정상 응답");
        assertEquals("CLOSED", r.circuitState(), "probe 성공 → CLOSED 복귀");
    }

    // ----- helpers -----

    private ElwSkewService buildService(KisMarketDataService marketData) {
        ElwProperties props = new ElwProperties();
        props.setEnabled(true);
        props.setChainUrl(wm.baseUrl() + "/uapi/elw/v1/quotations/cond-search");
        props.setChainTrId("TEST");
        props.setConcurrency(2);
        props.setIvTimeoutMs(2000);

        KisProperties kp = new KisProperties();
        kp.setAppKey("k");
        kp.setAppSecret("s");
        kp.setRequestsPerSecond(100);
        kp.setTokenRequestsPerSecond(10);
        kp.setMaxWaitMs(2000);

        KisAuthService auth = mock(KisAuthService.class);
        when(auth.getAccessToken()).thenReturn("token");

        KisRateLimiter rl = new KisRateLimiter(kp, java.time.Clock.systemUTC());
        KisHttpCaller http = new KisHttpCaller(rl, io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));
        KisElwChainProvider provider = new KisElwChainProvider(props, kp, auth, http);

        ElwSkewService svc = new ElwSkewService(props, provider, new ElwCircuitBreaker(), marketData);
        svc.init();
        return svc;
    }

    private static KisMarketDataService fakeUnderlyingAt(double price) {
        KisMarketDataService md = mock(KisMarketDataService.class);
        PricePoint pp = new PricePoint(LocalDate.now(), BigDecimal.valueOf(price));
        var chart = new KisMarketDataService.ChartResult("fake", "KRW", List.of(pp), BigDecimal.valueOf(price));
        when(md.getChartData(any(), any())).thenReturn(chart);
        return md;
    }

    private static String chainWithCallPutPair() {
        LocalDate expiry = LocalDate.now().plusMonths(3);
        String ex = expiry.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        return """
                {
                  "rt_cd": "0",
                  "output": [
                    {"mksc_shrn_iscd": "CALL1", "hts_kor_isnm": "콜",
                     "lstn_stcd_end_dt": "%s", "acpr": "2500", "stck_prpr": "120",
                     "elw_gbcd": "C", "isu_nm": "LP"},
                    {"mksc_shrn_iscd": "PUT1", "hts_kor_isnm": "풋",
                     "lstn_stcd_end_dt": "%s", "acpr": "2500", "stck_prpr": "95",
                     "elw_gbcd": "P", "isu_nm": "LP"}
                  ]
                }
                """.formatted(ex, ex);
    }

    private static void forceCooldownElapsed(ElwSkewService svc) throws Exception {
        Field breakerField = ElwSkewService.class.getDeclaredField("breaker");
        breakerField.setAccessible(true);
        ElwCircuitBreaker cb = (ElwCircuitBreaker) breakerField.get(svc);
        Field openedAt = ElwCircuitBreaker.class.getDeclaredField("openedAt");
        openedAt.setAccessible(true);
        ((AtomicLong) openedAt.get(cb)).set(System.currentTimeMillis() - 31_000);
    }
}

package com.backtesting.service.elw;

import com.backtesting.config.ElwProperties;
import com.backtesting.config.KisProperties;
import com.backtesting.model.elw.ElwModels.ElwContract;
import com.backtesting.model.elw.ElwModels.OptionType;
import com.backtesting.service.kis.KisAuthService;
import com.backtesting.service.kis.KisHttpCaller;
import com.backtesting.service.kis.KisRateLimiter;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KisElwChainProvider 의 실제 HTTP roundtrip + JSON 파싱 검증.
 * KIS 서버를 WireMock 으로 대체해 응답 페이로드·상태코드 경로를 실제로 탄다.
 */
class KisElwChainProviderWireMockTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void happyPath_parsesChainFromKisResponse() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(okJson("""
                        {
                          "rt_cd": "0",
                          "output": [
                            {
                              "mksc_shrn_iscd": "58J001",
                              "hts_kor_isnm": "한투KOSPI200 C 2500",
                              "lstn_stcd_end_dt": "20250914",
                              "acpr": "2500",
                              "stck_prpr": "120",
                              "elw_gbcd": "C",
                              "isu_nm": "한국투자증권"
                            },
                            {
                              "mksc_shrn_iscd": "58J002",
                              "hts_kor_isnm": "한투KOSPI200 P 2500",
                              "lstn_stcd_end_dt": "20250914",
                              "acpr": "2500",
                              "stck_prpr": "95",
                              "elw_gbcd": "P",
                              "isu_nm": "한국투자증권"
                            }
                          ]
                        }
                        """)));

        KisElwChainProvider provider = buildProvider();
        List<ElwContract> chain = provider.fetchChain("005930");

        assertEquals(2, chain.size());
        ElwContract call = chain.get(0);
        assertEquals("58J001", call.symbol());
        assertEquals(OptionType.CALL, call.type());
        assertEquals(2500, call.strike());
        assertEquals(120, call.marketPrice());
        assertEquals("한국투자증권", call.issuer());

        ElwContract put = chain.get(1);
        assertEquals(OptionType.PUT, put.type());
    }

    @Test
    void emptyOutput_returnsEmptyList() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(okJson("{\"rt_cd\": \"0\", \"output\": []}")));

        List<ElwContract> chain = buildProvider().fetchChain("005930");
        assertTrue(chain.isEmpty());
    }

    @Test
    void unknownResponseShape_returnsEmpty_noException() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(okJson("{\"rt_cd\": \"0\", \"something\": \"else\"}")));

        // output·output1·output2 모두 없음 → 파서가 빈 리스트 반환 (예외 X)
        List<ElwContract> chain = buildProvider().fetchChain("005930");
        assertTrue(chain.isEmpty());
    }

    @Test
    void malformedRow_skipped_othersKept() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(okJson("""
                        {
                          "output": [
                            {"mksc_shrn_iscd": "GOOD", "lstn_stcd_end_dt": "20250914",
                             "acpr": "2500", "stck_prpr": "100", "elw_gbcd": "C",
                             "hts_kor_isnm": "ok"},
                            {"mksc_shrn_iscd": "BAD", "acpr": "not-a-number",
                             "elw_gbcd": "C", "stck_prpr": "100",
                             "lstn_stcd_end_dt": "20250914", "hts_kor_isnm": "bad-strike"},
                            {"mksc_shrn_iscd": "NOEXPIRY", "acpr": "2500", "stck_prpr": "100",
                             "elw_gbcd": "C", "hts_kor_isnm": "missing-expiry"}
                          ]
                        }
                        """)));

        List<ElwContract> chain = buildProvider().fetchChain("005930");
        assertEquals(1, chain.size(), "GOOD 1개만 살아남음 (파싱 실패는 스킵)");
        assertEquals("GOOD", chain.get(0).symbol());
    }

    @Test
    void httpError_propagatesException_breakerCatches() {
        wm.stubFor(get(urlPathEqualTo("/uapi/elw/v1/quotations/cond-search"))
                .willReturn(serverError()));

        // provider 자체는 예외 전파 — 호출부(서킷 브레이커)가 이 예외를 실패 카운트로 집계
        assertThrows(RuntimeException.class, () -> buildProvider().fetchChain("005930"));
    }

    // ----- helpers -----

    private KisElwChainProvider buildProvider() {
        ElwProperties props = new ElwProperties();
        props.setEnabled(true);
        props.setChainUrl(wm.baseUrl() + "/uapi/elw/v1/quotations/cond-search");
        props.setChainTrId("FHPIF05030000");

        KisProperties kp = new KisProperties();
        kp.setAppKey("test-key");
        kp.setAppSecret("test-secret");
        kp.setRequestsPerSecond(100);
        kp.setTokenRequestsPerSecond(10);
        kp.setMaxWaitMs(2000);
        kp.setPaperTrading(false);

        KisAuthService auth = mock(KisAuthService.class);
        when(auth.getAccessToken()).thenReturn("test-token");

        KisRateLimiter rl = new KisRateLimiter(kp, java.time.Clock.systemUTC());
        KisHttpCaller http = new KisHttpCaller(rl, io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));
        return new KisElwChainProvider(props, kp, auth, http);
    }
}

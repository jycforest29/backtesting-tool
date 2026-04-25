package com.backtesting.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HttpRateLimitFilterTest {

    private HttpRateLimitProperties props;
    private HttpRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        props = new HttpRateLimitProperties();
        props.setEnabled(true);
        props.setMaxBuckets(1000);
        props.setIdleExpiryMinutes(30);

        HttpRateLimitProperties.Bucket def = new HttpRateLimitProperties.Bucket();
        def.setCapacity(3);
        def.setRefillPerMinute(60);   // 초당 1 토큰 — 버스트 3
        props.setDefault(def);

        HttpRateLimitProperties.Bucket trading = new HttpRateLimitProperties.Bucket();
        trading.setCapacity(2);
        trading.setRefillPerMinute(60);
        props.setTrading(trading);

        filter = new HttpRateLimitFilter(props);
    }

    @Test
    void burstIsAllowedUpToCapacity() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = req("GET", "/api/backtest", "1.2.3.4");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exceedingCapacityReturns429() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        // 정확히 capacity 만큼 소진.
        for (int i = 0; i < 3; i++) {
            filter.doFilter(req("GET", "/api/backtest", "1.2.3.4"), new MockHttpServletResponse(), chain);
        }
        // 4번째는 거부 — 동일 IP.
        MockHttpServletRequest req = req("GET", "/api/backtest", "1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        assertThat(res.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void differentIpsHaveSeparateBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 3; i++) {
            filter.doFilter(req("GET", "/api/backtest", "1.1.1.1"), new MockHttpServletResponse(), chain);
        }
        // 다른 IP — 새 버킷.
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req("GET", "/api/backtest", "2.2.2.2"), res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void tradingBucketUsesStricterLimit() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        // trading capacity=2
        for (int i = 0; i < 2; i++) {
            filter.doFilter(req("POST", "/api/trading/order", "3.3.3.3"), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req("POST", "/api/trading/order", "3.3.3.3"), res, chain);
        assertThat(res.getStatus()).isEqualTo(429);
    }

    @Test
    void actuatorHealthSkipsFilter() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        // 수백 번 호출해도 429 절대 나오면 안 됨 (skip 대상이므로).
        for (int i = 0; i < 200; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req("GET", "/actuator/health", "9.9.9.9"), res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void disabledPropertyShortCircuits() throws Exception {
        props.setEnabled(false);
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 10; i++) {
            filter.doFilter(req("GET", "/api/backtest", "1.2.3.4"), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(10)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void xForwardedForHeaderUsed() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req1 = req("GET", "/api/backtest", "10.0.0.1");
        req1.addHeader("X-Forwarded-For", "100.1.1.1, 10.0.0.1");
        MockHttpServletRequest req2 = req("GET", "/api/backtest", "10.0.0.1");
        req2.addHeader("X-Forwarded-For", "100.1.1.1, 10.0.0.1");

        // 같은 XFF 첫 IP — 같은 버킷 사용.
        for (int i = 0; i < 3; i++) {
            filter.doFilter(req1, new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req2, res, chain);
        assertThat(res.getStatus()).isEqualTo(429);
    }

    private MockHttpServletRequest req(String method, String uri, String ip) throws IOException {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr(ip);
        return r;
    }
}

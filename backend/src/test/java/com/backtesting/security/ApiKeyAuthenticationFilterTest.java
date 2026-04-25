package com.backtesting.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ApiKeyAuthenticationFilterTest {

    private ApiKeyRegistry registry;
    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        registry = new ApiKeyRegistry(List.of(
                key("trader-k",  "trader", SecurityProperties.Role.TRADER, SecurityProperties.Role.VIEWER),
                key("admin-k",   "admin",  SecurityProperties.Role.ADMIN)));
        filter = new ApiKeyAuthenticationFilter(registry);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void validKeyAttachesAuthenticationToContext() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trading/status");
        req.addHeader(ApiKeyAuthenticationFilter.HEADER, "trader-k");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("trader");
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_TRADER", "ROLE_VIEWER");
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void missingHeaderLeavesContextEmpty() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trading/status");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void unknownKeyLeavesContextEmpty() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trading/status");
        req.addHeader(ApiKeyAuthenticationFilter.HEADER, "wrong-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // chain 은 계속 — 이후 authorizeHttpRequests 매처가 401/403 판정.
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void existingAuthenticationIsNotOverridden() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trading/status");
        req.addHeader(ApiKeyAuthenticationFilter.HEADER, "trader-k");
        ApiKeyAuthenticationToken preexisting = new ApiKeyAuthenticationToken(
                "pre-set", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER")));
        SecurityContextHolder.getContext().setAuthentication(preexisting);

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("pre-set");
    }

    private static SecurityProperties.ApiKey key(String val, String principal, SecurityProperties.Role... roles) {
        SecurityProperties.ApiKey k = new SecurityProperties.ApiKey();
        k.setKey(val);
        k.setPrincipal(principal);
        k.setRoles(Set.of(roles));
        return k;
    }
}

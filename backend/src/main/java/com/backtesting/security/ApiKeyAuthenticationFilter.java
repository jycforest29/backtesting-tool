package com.backtesting.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * X-API-Key 헤더 파싱 + SecurityContext 주입. 실패 시 아무것도 하지 않는다
 * (SecurityFilterChain 의 authenticated() 매처가 이후 401 을 내도록).
 *
 * 한 요청에 한 번만 실행되고, 이미 인증된 컨텍스트가 있으면 건드리지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyRegistry registry;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String presented = req.getHeader(HEADER);
            if (presented != null && !presented.isBlank()) {
                registry.resolve(presented).ifPresentOrElse(p -> {
                    ApiKeyAuthenticationToken auth =
                            new ApiKeyAuthenticationToken(p.name(), p.authorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // MDC 에 principal 추가 — 감사 로그 상관키.
                    org.slf4j.MDC.put("principal", p.name());
                }, () -> log.warn("Unknown API key presented path={} ip={}",
                        req.getRequestURI(), req.getRemoteAddr()));
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            org.slf4j.MDC.remove("principal");
        }
    }
}

package com.backtesting.common;

import com.backtesting.common.error.ErrorCode;
import com.backtesting.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청 진입 시 RequestContext 설정 (X-Deadline-Ms 헤더 또는 기본 30s).
 * X-Trace-Id 헤더가 오면 그걸 그대로 사용 — 업스트림 트레이싱 연결용.
 * MDC 에도 traceId 를 심어 로그에 자동 포함.
 *
 * DeadlineExceededException → 504 Gateway Timeout (표준 {@link com.backtesting.common.error.ErrorResponse}).
 */
@Slf4j
@Component
public class DeadlineFilter extends OncePerRequestFilter implements Ordered {

    private static final String HEADER_DEADLINE = "X-Deadline-Ms";
    private static final String HEADER_TRACE = "X-Trace-Id";
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        long timeoutMs = parseLong(req.getHeader(HEADER_DEADLINE), DEFAULT_TIMEOUT_MS);
        String trace = req.getHeader(HEADER_TRACE);

        RequestContext ctx = RequestContext.withTimeout(timeoutMs, trace);
        RequestContext.set(ctx);
        MDC.put("traceId", ctx.traceId());
        res.setHeader(HEADER_TRACE, ctx.traceId());

        try {
            chain.doFilter(req, res);
        } catch (RequestContext.DeadlineExceededException e) {
            log.warn("Deadline exceeded: {} {} — {}", req.getMethod(), req.getRequestURI(), e.getMessage());
            ErrorResponseWriter.write(req, res, ErrorCode.DEADLINE_EXCEEDED, e.getMessage());
        } finally {
            RequestContext.clear();
            MDC.remove("traceId");
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
}

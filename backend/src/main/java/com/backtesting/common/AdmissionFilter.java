package com.backtesting.common;

import com.backtesting.common.error.ErrorCode;
import com.backtesting.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청 진입 게이트.
 *  - X-Priority 헤더 또는 URL 패턴으로 priority 결정
 *  - AdmissionController.tryAcquire 로 허가 확인
 *  - 거부 시 503 + Retry-After
 *
 * DeadlineFilter 뒤에 실행 — deadline 은 거부된 요청에도 계산되어 log 에 남음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdmissionFilter extends OncePerRequestFilter implements Ordered {

    private final AdmissionController admission;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        Priority p = resolvePriority(req);
        if (!admission.tryAcquire(p)) {
            log.warn("Admission rejected priority={} path={} inFlight={}",
                    p, req.getRequestURI(), admission.currentInFlight());
            ErrorResponseWriter.write(req, res, ErrorCode.OVERLOADED,
                    "서버가 과부하 상태입니다 (priority=" + p + ")", 2L);
            return;
        }
        try {
            chain.doFilter(req, res);
        } finally {
            admission.release();
        }
    }

    /**
     * Priority 해석 순서:
     *  1) X-Priority 헤더 (명시적)
     *  2) URL 기반 자동 분류 (/api/trading/order → CRITICAL, /api/quant/backtest → BULK)
     *  3) 기본 STANDARD
     */
    private Priority resolvePriority(HttpServletRequest req) {
        String hdr = req.getHeader("X-Priority");
        if (hdr != null && !hdr.isBlank()) return Priority.parse(hdr);

        String path = req.getRequestURI();
        if (path.startsWith("/api/trading/order")) return Priority.CRITICAL;
        if (path.startsWith("/api/quant/backtest")) return Priority.BULK;
        if (path.startsWith("/api/quant/preview")) return Priority.BULK;
        if (path.startsWith("/api/elw/skew")) return Priority.BULK;
        return Priority.STANDARD;
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 20; } // DeadlineFilter(+10) 다음
}

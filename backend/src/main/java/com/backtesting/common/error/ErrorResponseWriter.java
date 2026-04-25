package com.backtesting.common.error;

import com.backtesting.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * Servlet 필터/보안 필터용 ErrorResponse JSON 작성 유틸.
 *
 * @RestControllerAdvice 가 미치지 않는 바깥 (필터, AuthenticationEntryPoint, AccessDeniedHandler)
 * 에서도 일관된 에러 스키마를 유지하기 위한 공통 헬퍼.
 */
public final class ErrorResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ErrorResponseWriter() {}

    public static void write(HttpServletRequest req, HttpServletResponse res,
                              ErrorCode code, String message) throws IOException {
        write(req, res, code, message, null);
    }

    public static void write(HttpServletRequest req, HttpServletResponse res,
                              ErrorCode code, String message, Long retryAfterSec) throws IOException {
        if (res.isCommitted()) return;
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        if (retryAfterSec != null && retryAfterSec > 0) {
            res.setHeader("Retry-After", String.valueOf(retryAfterSec));
        }
        ErrorResponse body = ErrorResponse.of(code, message, currentTraceId(), req.getRequestURI());
        MAPPER.writeValue(res.getWriter(), body);
    }

    private static String currentTraceId() {
        RequestContext ctx = RequestContext.current();
        return ctx != null ? ctx.traceId() : null;
    }
}

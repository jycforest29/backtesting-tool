package com.backtesting.security;

import com.backtesting.common.error.ErrorCode;
import com.backtesting.common.error.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증 실패 시(Authentication 객체 없음) 401 + 표준 ErrorResponse.
 */
@Slf4j
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
            throws IOException {
        log.warn("Unauthenticated access path={} ip={} reason={}",
                req.getRequestURI(), req.getRemoteAddr(), ex.getMessage());
        ErrorResponseWriter.write(req, res, ErrorCode.UNAUTHENTICATED,
                "유효한 API 키가 필요합니다 (X-API-Key 헤더)");
    }
}

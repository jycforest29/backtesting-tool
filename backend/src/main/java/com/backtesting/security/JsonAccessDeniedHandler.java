package com.backtesting.security;

import com.backtesting.common.error.ErrorCode;
import com.backtesting.common.error.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증은 됐으나 권한 부족 → 403 + 표준 ErrorResponse.
 */
@Slf4j
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex)
            throws IOException {
        log.warn("Access denied path={} reason={}", req.getRequestURI(), ex.getMessage());
        ErrorResponseWriter.write(req, res, ErrorCode.FORBIDDEN,
                "해당 리소스에 대한 권한이 없습니다");
    }
}

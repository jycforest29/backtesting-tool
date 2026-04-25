package com.backtesting.config;

import com.backtesting.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    private final AuditLogService auditLogService;
    private static final String START_TIME_ATTR = "audit_start_time";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        String path = request.getRequestURI();

        // Skip non-API paths and static resources
        if (!path.startsWith("/api")) return;

        long startTime = (long) request.getAttribute(START_TIME_ATTR);
        long duration = System.currentTimeMillis() - startTime;

        String method = request.getMethod();
        String user = request.getRemoteAddr();
        int status = response.getStatus();

        // Try to get request body
        String body = null;
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] buf = wrapper.getContentAsByteArray();
            if (buf.length > 0) {
                body = new String(buf, 0, Math.min(buf.length, 1000));
            }
        }

        String error = ex != null ? ex.getMessage() : null;

        auditLogService.record(method, path, user, body, status, duration, error);
    }
}
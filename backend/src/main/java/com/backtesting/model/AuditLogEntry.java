package com.backtesting.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AuditLogEntry(
        long id,
        LocalDateTime timestamp,
        String method,        // GET, POST, etc.
        String path,          // /api/backtest
        String action,        // human-readable: "Portfolio Backtest", "Subscribe Live"
        String user,          // IP or user ID (no auth yet, so IP)
        String requestBody,   // truncated request payload
        int responseStatus,   // 200, 400, 500
        long durationMs,      // how long it took
        String level,         // INFO, WARN, ERROR
        String detail         // extra info / error message
) {}

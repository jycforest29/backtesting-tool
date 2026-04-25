package com.backtesting.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuditLogEntry {
    private long id;
    private LocalDateTime timestamp;
    private String method;       // GET, POST, etc.
    private String path;         // /api/backtest
    private String action;       // human-readable: "Portfolio Backtest", "Subscribe Live"
    private String user;         // IP or user ID (no auth yet, so IP)
    private String requestBody;  // truncated request payload
    private int responseStatus;  // 200, 400, 500
    private long durationMs;     // how long it took
    private String level;        // INFO, WARN, ERROR
    private String detail;       // extra info / error message
}
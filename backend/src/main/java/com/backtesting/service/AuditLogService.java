package com.backtesting.service;

import com.backtesting.model.AuditLogEntry;
import com.backtesting.persistence.AuditLogRepository;
import com.backtesting.persistence.AuditLogRowEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * API 감사 로그 — DB 영속화 버전.
 *
 * 설계:
 *   - 핫 패스(record)는 메모리 큐에 먼저 넣고 비동기 flush 대신 동기 save (감사로그 누락 방지).
 *   - 최근 N건은 인메모리 캐시 유지 — 조회 빠르게.
 *   - 보관 기간 넘은 레코드는 매일 새벽 purge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** 보관 기간. 운영에서는 환경변수로 뺄 수 있음. */
    private static final int RETENTION_DAYS = 60;
    /** 메모리 캐시 상한. */
    private static final int MEMORY_CACHE_SIZE = 1000;

    private final AuditLogRepository repo;

    /** 최근 N건 캐시 (역순). 동시성 허용. */
    private final Deque<AuditLogEntry> memoryCache = new ConcurrentLinkedDeque<>();

    private static final Map<String, String> ACTION_MAP = Map.ofEntries(
            Map.entry("POST /api/backtest", "Single Backtest"),
            Map.entry("POST /api/portfolio-backtest", "Portfolio Backtest"),
            Map.entry("GET /api/search", "Symbol Search"),
            Map.entry("GET /api/presets", "Load Presets"),
            Map.entry("GET /api/supply-demand", "Supply/Demand Analysis"),
            Map.entry("GET /api/supply-demand/market", "Market Supply/Demand"),
            Map.entry("POST /api/live/subscribe", "Subscribe Live Price"),
            Map.entry("POST /api/live/unsubscribe", "Unsubscribe Live Price"),
            Map.entry("POST /api/live/refresh", "Manual Price Refresh"),
            Map.entry("GET /api/live/prices", "Get Live Prices"),
            Map.entry("POST /api/stress-test", "Stress Test"),
            Map.entry("GET /api/audit-log", "View Audit Log"),
            Map.entry("POST /api/trading/order", "Place Order"),
            Map.entry("POST /api/trading/oco", "Register OCO"),
            Map.entry("DELETE /api/trading/oco/{id}", "Cancel OCO"),
            Map.entry("POST /api/quant/enable/{type}", "Enable Quant Strategy"),
            Map.entry("POST /api/quant/disable/{type}", "Disable Quant Strategy"),
            Map.entry("POST /api/quant/run/{type}", "Run Quant Strategy")
    );

    @Transactional
    public void record(String method, String path, String user, String requestBody,
                       int status, long durationMs, String errorDetail) {
        String level = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";
        String action = ACTION_MAP.getOrDefault(method + " " + path, method + " " + path);

        String truncatedBody = requestBody;
        if (truncatedBody != null && truncatedBody.length() > 500) {
            truncatedBody = truncatedBody.substring(0, 500) + "...";
        }

        AuditLogRowEntity row = AuditLogRowEntity.builder()
                .timestamp(LocalDateTime.now())
                .method(method)
                .path(path)
                .action(action)
                .user(user != null ? user : "anonymous")
                .requestBody(truncatedBody)
                .responseStatus(status)
                .durationMs(durationMs)
                .level(level)
                .detail(errorDetail)
                .build();

        try {
            row = repo.save(row);
        } catch (Exception e) {
            // 감사로그 DB 실패는 애플리케이션 동작 막지 않는다 — 파일 로거가 fallback.
            log.warn("Audit log persist failed: {}", e.getMessage());
        }

        AuditLogEntry entry = row.toDomain();
        memoryCache.addFirst(entry);
        while (memoryCache.size() > MEMORY_CACHE_SIZE) memoryCache.removeLast();

        if ("ERROR".equals(level)) {
            log.error("[AUDIT] {} {} {} - {} {}ms - {}", user, method, path, status, durationMs, errorDetail);
        } else {
            log.info("[AUDIT] {} {} {} - {} {}ms", user, method, path, status, durationMs);
        }
    }

    /** 최근 이력 조회. 메모리 캐시 우선, 부족하면 DB. */
    public List<AuditLogEntry> getEntries(int limit, String levelFilter, String actionFilter) {
        int effectiveLimit = Math.min(limit, 500);
        Pageable page = PageRequest.of(0, effectiveLimit);

        List<AuditLogRowEntity> rows;
        if (levelFilter != null && !levelFilter.isEmpty()) {
            rows = repo.findByLevelOrderByTimestampDesc(levelFilter, page);
        } else if (actionFilter != null && !actionFilter.isEmpty()) {
            rows = repo.findByActionContainingIgnoreCaseOrderByTimestampDesc(actionFilter, page);
        } else {
            rows = repo.findAllByOrderByTimestampDesc(page);
        }
        return rows.stream().map(AuditLogRowEntity::toDomain).toList();
    }

    public Map<String, Object> getStats() {
        long total = repo.count();
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (var lc : repo.countByLevel()) byLevel.put(lc.getLevel(), lc.getCnt());

        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay();
        long todayCount = repo.findAllByOrderByTimestampDesc(PageRequest.of(0, 1000)).stream()
                .filter(e -> e.getTimestamp().isAfter(todayStart))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalEntries", total);
        result.put("errors", byLevel.getOrDefault("ERROR", 0L));
        result.put("warnings", byLevel.getOrDefault("WARN", 0L));
        result.put("info", byLevel.getOrDefault("INFO", 0L));
        result.put("todayCount", todayCount);
        result.put("retentionDays", RETENTION_DAYS);
        return result;
    }

    /** 매일 03:00 KST에 보관기간 초과 레코드 삭제. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeOld() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = repo.deleteByTimestampBefore(threshold);
        if (deleted > 0) log.info("Audit log purge: deleted {} rows older than {}", deleted, threshold);
    }
}

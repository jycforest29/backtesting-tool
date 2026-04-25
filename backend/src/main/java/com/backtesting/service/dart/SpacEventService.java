package com.backtesting.service.dart;

import com.backtesting.config.DartProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPAC 합병 관련 공시 이벤트 캐시.
 * DART /api/list.json 의 주요사항보고서(pblntf_ty=B)에서 "합병" 키워드 포함 공시일자를
 * stockCode → 날짜 리스트(오름차순)로 캐싱.
 *
 * 룩어헤드 방지: latestAnnouncementBefore 는 asOfDate 이전 날짜만 반환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpacEventService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartClient client;
    private final DartProperties props;

    /** stockCode -> 합병 관련 공시 일자들(오름차순). */
    private final Map<String, List<LocalDate>> cache = new ConcurrentHashMap<>();

    public Optional<LocalDate> latestAnnouncementBefore(String stockCode, String corpCode, LocalDate asOfDate) {
        List<LocalDate> events = cache.computeIfAbsent(stockCode, k -> fetchAnnouncements(corpCode, stockCode));
        LocalDate latest = null;
        for (LocalDate d : events) {
            if (d.isAfter(asOfDate)) break;
            latest = d;
        }
        return Optional.ofNullable(latest);
    }

    public void clear() {
        cache.clear();
    }

    public int cachedCount() {
        return cache.size();
    }

    private List<LocalDate> fetchAnnouncements(String corpCode, String stockCode) {
        if (!props.isConfigured()) return List.of();
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusYears(10);
            List<LocalDate> out = new ArrayList<>();
            int page = 1;
            while (true) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("corp_code", corpCode);
                params.put("bgn_de", start.format(YMD));
                params.put("end_de", end.format(YMD));
                params.put("pblntf_ty", "B");
                params.put("page_no", String.valueOf(page));
                params.put("page_count", "100");
                JsonNode root = client.getJson("/list.json", params);
                String status = text(root.get("status"));
                if ("013".equals(status)) break; // 데이터 없음
                if (!"000".equals(status)) {
                    log.warn("SPAC event fetch {} status={} msg={}", stockCode, status, text(root.get("message")));
                    break;
                }
                JsonNode list = root.get("list");
                if (list == null || !list.isArray()) break;
                for (JsonNode n : list) {
                    String reportName = text(n.get("report_nm"));
                    if (reportName == null || !reportName.contains("합병")) continue;
                    String d = text(n.get("rcept_dt"));
                    if (d == null || d.length() < 8) continue;
                    try {
                        out.add(LocalDate.parse(d.substring(0, 8), YMD));
                    } catch (Exception ignored) {
                    }
                }
                int totalPage = intVal(root.get("total_page"), 1);
                if (page >= totalPage) break;
                page++;
            }
            Collections.sort(out);
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("SPAC event fetch failed {} ({}): {}", stockCode, corpCode, e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText(null);
    }

    private static int intVal(JsonNode n, int fallback) {
        if (n == null || n.isNull()) return fallback;
        try { return n.asInt(fallback); } catch (Exception e) { return fallback; }
    }
}

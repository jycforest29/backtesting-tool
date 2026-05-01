package com.backtesting.service.kis;

import com.backtesting.config.KisProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KRX 영업일/휴장일 캘린더 — KIS chk-holiday API 기반.
 *
 * 왜 필요한가:
 *   기존 스케줄러는 cron MON-FRI 만 거름. 신정·설·추석·삼일절·어린이날·임시휴장 등은
 *   발화 → KIS 시장가 주문 거부 → 메일·SSE 에 오류 outcome 노이즈.
 *
 * 흐름:
 *   1. 부팅 시 @PostConstruct 로 today 부터 호출 → 응답 ~3개월치 캐시
 *   2. 매일 새벽 1시 갱신 — 임시휴장 등 변경 대비
 *   3. {@link #isOpenDay} 시점 cache miss 면 lazy fetch
 *
 * Fallback 정책 (사용자 결정):
 *   KIS 미설정 / fetch 실패 시 → 보수적으로 false (휴장 간주). 발화 안 함이 잘못 발화보다 안전.
 *   하루 놓치는 비용 < 휴장일에 시장가 주문 거부 + 메일 폭주.
 */
@Slf4j
@Service
public class KrxCalendarService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisAuthService auth;
    private final KisHttpCaller http;
    private final KisProperties kisProps;

    /** date → 개장일 여부. true=영업일, false=휴장. KIS 응답 그대로 캐시. */
    private final ConcurrentHashMap<LocalDate, Boolean> cache = new ConcurrentHashMap<>();

    public KrxCalendarService(KisAuthService auth, KisHttpCaller http, KisProperties kisProps) {
        this.auth = auth;
        this.http = http;
        this.kisProps = kisProps;
    }

    @PostConstruct
    void warmup() {
        if (!kisProps.isConfigured()) {
            log.warn("KRX calendar: KIS not configured at boot — cache empty, scheduler will conservatively skip");
            return;
        }
        try {
            refresh();
        } catch (Exception e) {
            log.error("KRX calendar warmup failed (boot continues): {}", e.getMessage(), e);
        }
    }

    /** 매일 새벽 1시 캐시 갱신 — KRX 임시휴장 / 추가 공휴일이 KIS 서버 데이터에 반영될 가능성 대비. */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
    public void refresh() {
        if (!kisProps.isConfigured()) return;
        try {
            fetchAndCache(LocalDate.now(SEOUL));
            log.info("KRX calendar refreshed: cache size = {}", cache.size());
        } catch (Exception e) {
            log.warn("KRX calendar refresh failed (continuing with stale cache): {}", e.getMessage());
        }
    }

    /**
     * 영업일 여부. 캐시 hit 우선, miss 시 lazy fetch.
     * KIS 미설정 / fetch 실패 시 false (보수적 fallback — 휴장 간주).
     */
    public boolean isOpenDay(LocalDate date) {
        Boolean cached = cache.get(date);
        if (cached != null) return cached;
        if (!kisProps.isConfigured()) return false;
        try {
            fetchAndCache(date);
            return cache.getOrDefault(date, false);
        } catch (Exception e) {
            log.warn("KRX calendar fetch failed for {}: {} — conservatively treating as holiday",
                    date, e.getMessage());
            return false;
        }
    }

    /** KIS chk-holiday 1회 호출 → 응답의 모든 row 를 캐시에 채움 (≈3개월치). */
    private void fetchAndCache(LocalDate from) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("BASS_DT", from.format(YMD));
        params.put("CTX_AREA_NK", "");
        params.put("CTX_AREA_FK", "");

        // 시세성 API 라 실/모의 동일 tr_id (KIS 정책). 모의에서 401/EGW 떨어지면 paper tr_id 분기 추가.
        Map<String, String> headers = http.baseHeaders(
                auth.getProps(), auth.getAccessToken(), "CTCA0903R");

        JsonNode res = http.get(
                auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/chk-holiday",
                params, headers);

        JsonNode output = res.path("output");
        if (!output.isArray() || output.isEmpty()) {
            log.warn("KRX calendar: empty output for from={}", from);
            return;
        }
        int added = 0;
        for (JsonNode row : output) {
            String bassDt = row.path("bass_dt").asText(null);
            String opndYn = row.path("opnd_yn").asText(null);
            if (bassDt == null || bassDt.length() != 8 || opndYn == null) continue;
            try {
                cache.put(LocalDate.parse(bassDt, YMD), "Y".equalsIgnoreCase(opndYn));
                added++;
            } catch (Exception ignore) {
                // 잘못된 row 1건이 전체 응답을 망치지 않도록 격리.
            }
        }
        log.debug("KRX calendar fetched from {}: +{} entries (cache size = {})", from, added, cache.size());
    }

    /** 테스트/관측용 — 캐시 entry 수. */
    public int cacheSize() { return cache.size(); }
}

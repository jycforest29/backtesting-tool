package com.backtesting.service;

import com.backtesting.config.AlertProperties;
import com.backtesting.model.AssetType;
import com.backtesting.service.kis.KisMarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 장중 거래량 급증 스캐너.
 * 2분 주기로 stocks-master KR 종목들의 분봉 데이터 조회 → 최근 N분 거래량이
 * 직전 M분 평균의 threshold% 이상이면 프론트에 토스트 알림 푸시 (/topic/alerts).
 * 같은 종목은 cooldown 동안 재알림 안 함.
 * 장 시간(09:00~15:30 KST) 외엔 자동으로 스킵.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolumeSpikeScannerService {

    private final AlertProperties alertProps;
    private final StockMasterService masterService;
    private final KisMarketDataService marketData;
    private final SimpMessagingTemplate messaging;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final Map<String, LocalDateTime> lastAlerted = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private void initEnabled() {
        if (initialized) return;
        enabled.set(alertProps.getVolumeSpike().isEnabled());
        initialized = true;
        log.info("VolumeSpikeScanner initial enabled={}", enabled.get());
    }

    public boolean isEnabled() {
        initEnabled();
        return enabled.get();
    }

    public boolean setEnabled(boolean value) {
        initEnabled();
        enabled.set(value);
        log.info("VolumeSpikeScanner enabled set to {}", value);
        return value;
    }

    public Map<String, Object> status() {
        var cfg = alertProps.getVolumeSpike();
        return Map.of(
                "enabled", isEnabled(),
                "recentMinutes", cfg.getRecentMinutes(),
                "compareMinutes", cfg.getCompareMinutes(),
                "thresholdPercent", cfg.getThresholdPercent(),
                "cooldownMinutes", cfg.getCooldownMinutes(),
                "poolSize", masterService.getAll(AssetType.KR_STOCK).size()
        );
    }

    @Scheduled(fixedDelayString = "#{@alertProperties.volumeSpike.scanIntervalSeconds * 1000}",
            initialDelay = 60_000)
    public void scan() {
        initEnabled();
        if (!enabled.get()) return;

        LocalTime now = LocalTime.now(SEOUL);
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) return;

        var cfg = alertProps.getVolumeSpike();
        var pool = masterService.getAll(AssetType.KR_STOCK);
        int alertsFired = 0;

        for (var entry : pool) {
            if (!enabled.get()) break; // 중간에 끈 경우 즉시 중단
            try {
                SpikeResult result = detectSpike(entry.code(), entry.name(), cfg);
                if (result != null) {
                    fireAlert(result, cfg.getCooldownMinutes());
                    alertsFired++;
                }
            } catch (Exception e) {
                log.debug("scan error {}: {}", entry.code(), e.getMessage());
            }
        }
        if (alertsFired > 0) {
            log.info("VolumeSpikeScanner cycle: {} alerts fired", alertsFired);
        }
    }

    private record SpikeResult(String code, String name, double percent, long recentVol,
                               double prevAvgVol, BigDecimal price, int recentMin, int compareMin) {}

    private SpikeResult detectSpike(String code, String name, AlertProperties.VolumeSpike cfg) {
        List<KisMarketDataService.MinuteBar> bars = marketData.getRecentMinuteBars(code);
        int needed = cfg.getRecentMinutes() + cfg.getCompareMinutes();
        if (bars.size() < needed) return null;

        List<KisMarketDataService.MinuteBar> recent = bars.subList(0, cfg.getRecentMinutes());
        List<KisMarketDataService.MinuteBar> prev = bars.subList(
                cfg.getRecentMinutes(),
                cfg.getRecentMinutes() + cfg.getCompareMinutes());

        long recentVol = recent.stream().mapToLong(KisMarketDataService.MinuteBar::volume).sum();
        long prevTotal = prev.stream().mapToLong(KisMarketDataService.MinuteBar::volume).sum();
        if (prevTotal <= 0) return null;

        double prevAvg = (double) prevTotal / cfg.getCompareMinutes();
        double recentAvg = (double) recentVol / cfg.getRecentMinutes();
        double percent = recentAvg / prevAvg * 100.0;
        if (percent < cfg.getThresholdPercent()) return null;

        BigDecimal price = recent.get(0).price();
        return new SpikeResult(code, name, percent, recentVol, prevAvg, price,
                cfg.getRecentMinutes(), cfg.getCompareMinutes());
    }

    private void fireAlert(SpikeResult r, int cooldownMinutes) {
        LocalDateTime last = lastAlerted.get(r.code);
        LocalDateTime now = LocalDateTime.now(SEOUL);
        if (last != null && last.plusMinutes(cooldownMinutes).isAfter(now)) return;
        lastAlerted.put(r.code, now);

        // 프론트 토스트 포맷: "종목명(코드) 최근 N분 거래량 P% 폭증 · 현재 ...원"
        String text = String.format(
                "%s(%s) 최근 %d분 거래량 %.0f%% 폭증 · 현재 %,d원",
                r.name, r.code, r.recentMin, r.percent, r.price.longValue());

        Map<String, Object> payload = Map.of(
                "kind", "VOLUME_SPIKE",
                "symbol", r.code,
                "name", r.name,
                "text", text,
                "percent", Math.round(r.percent),
                "price", r.price,
                "recentMinutes", r.recentMin,
                "compareMinutes", r.compareMin,
                "ts", now.toString()
        );
        messaging.convertAndSend("/topic/alerts", payload);
        log.info("SPIKE alert: {}", text);
    }
}

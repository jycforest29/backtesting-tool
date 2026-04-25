package com.backtesting.config;

import com.backtesting.service.dart.DartCorpCodeService;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 커스텀 /actuator/health 컴포넌트 — 외부 의존성 상태 한 눈에.
 * 실패해도 앱은 UP 유지 (파셜 기능 동작 가능) — status는 DOWN이 아니라 디테일로 표시.
 */
@Component("tradingServices")
@RequiredArgsConstructor
public class TradingHealthIndicator implements HealthIndicator {

    private final KisProperties kisProps;
    private final DartProperties dartProps;
    private final DartCorpCodeService corpCode;
    private final FundamentalDataService fundamentals;
    private final KospiUniverseService universe;

    @Override
    public Health health() {
        Health.Builder b = Health.up();

        b.withDetail("kis.configured", kisProps.isConfigured())
         .withDetail("kis.paperTrading", kisProps.isPaperTrading())
         .withDetail("kis.account", kisProps.getAccountNumber() != null
                 ? "configured" : "missing");

        b.withDetail("dart.configured", dartProps.isConfigured())
         .withDetail("dart.corpCodeCached", corpCode.cachedCount())
         .withDetail("dart.fundamentalCache", fundamentals.cacheSize());

        b.withDetail("universe.size", universe.all().size());

        // 필수 KIS 키가 없으면 DOWN (실매매 전혀 불가)
        if (!kisProps.isConfigured()) {
            return b.status("OUT_OF_SERVICE")
                    .withDetail("reason", "KIS credentials not configured")
                    .build();
        }

        return b.build();
    }
}

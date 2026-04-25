package com.backtesting.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 트레이딩 도메인 비즈니스 메트릭.
 *
 * Prometheus 포맷 — /actuator/prometheus 로 스크랩.
 *
 * 명명 규칙:
 *   - 카운터: <domain>_<event>_total (Prometheus 관례: _total 접미사)
 *   - 타이머: <domain>_<operation>_seconds (latency bucket/histogram 자동 생성)
 *
 * Tag 전략:
 *   - market: KR_STOCK / US_STOCK / JP_STOCK
 *   - side:   BUY / SELL
 *   - reason: REJECTED 이유 분류 (짧은 enum — cardinality 주의)
 *
 * Cardinality 주의: 주문별 symbol 태그는 절대 붙이지 않는다 (KRX 만 해도 2800 종목 → 지표 폭발).
 * symbol 수준 분석이 필요하면 logs + trace 를 쓴다.
 */
@Component
public class TradingMetrics {

    private final MeterRegistry registry;

    private final Counter ordersSubmitted;
    private final Counter ordersAccepted;
    private final Counter ordersRejected;
    private final Counter ordersFailed;
    private final Counter lossGuardBlocked;

    private final ConcurrentMap<String, Counter> rejectByReason = new ConcurrentHashMap<>();

    public TradingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ordersSubmitted = Counter.builder("trading_orders_submitted_total")
                .description("OrderExecutionService.execute() 진입 횟수 (PLACING 기록 후)")
                .register(registry);
        this.ordersAccepted = Counter.builder("trading_orders_accepted_total")
                .description("KIS 가 ACCEPTED 로 응답한 주문 수")
                .register(registry);
        this.ordersRejected = Counter.builder("trading_orders_rejected_total")
                .description("KIS 가 REJECTED 로 응답한 주문 수")
                .register(registry);
        this.ordersFailed = Counter.builder("trading_orders_failed_total")
                .description("HTTP 예외 등으로 KIS 처리 여부가 모호한 주문 수")
                .register(registry);
        this.lossGuardBlocked = Counter.builder("trading_loss_guard_blocked_total")
                .description("일일 손실 한도 초과로 컨트롤러에서 즉시 차단된 주문 수")
                .register(registry);
    }

    public void onSubmit(String market) {
        ordersSubmitted.increment();
    }

    public void onAccepted(String market, String side, Duration latency) {
        ordersAccepted.increment();
        registry.timer("trading_orders_accepted_seconds",
                Tags.of("market", safeTag(market), "side", safeTag(side)))
                .record(latency);
    }

    public void onRejected(String market, String side, String reason, Duration latency) {
        ordersRejected.increment();
        registry.timer("trading_orders_rejected_seconds",
                Tags.of("market", safeTag(market), "side", safeTag(side)))
                .record(latency);
        // reason 카디널리티 방어 — 최대 32 개까지만 개별 태그.
        if (rejectByReason.size() < 32 || rejectByReason.containsKey(reason)) {
            rejectByReason.computeIfAbsent(reason, r -> Counter.builder("trading_orders_rejected_by_reason_total")
                    .tags(Tags.of("reason", safeTag(r)))
                    .register(registry)).increment();
        } else {
            rejectByReason.computeIfAbsent("OTHER", r -> Counter.builder("trading_orders_rejected_by_reason_total")
                    .tags(Tags.of("reason", "OTHER")).register(registry)).increment();
        }
    }

    public void onFailed(String market, String side, Duration latency) {
        ordersFailed.increment();
        registry.timer("trading_orders_failed_seconds",
                Tags.of("market", safeTag(market), "side", safeTag(side)))
                .record(latency);
    }

    public void onLossGuardBlocked() {
        lossGuardBlocked.increment();
    }

    /** 외부 HTTP 호출 latency (KIS, DART) — 도메인/엔드포인트 단위 timer. */
    public Timer upstreamTimer(String upstream, String endpoint, String outcome) {
        return Timer.builder("upstream_http_seconds")
                .description("외부 시스템 HTTP 호출 지연")
                .tags(Tags.of(
                        "upstream", safeTag(upstream),
                        "endpoint", safeTag(endpoint),
                        "outcome",  safeTag(outcome)))
                .publishPercentileHistogram()
                .register(registry);
    }

    /** 태그 값 cardinality 제한. null / 빈 / 너무 긴 값 정규화. */
    private static String safeTag(String v) {
        if (v == null || v.isBlank()) return "unknown";
        if (v.length() > 32) return v.substring(0, 32);
        return v;
    }
}

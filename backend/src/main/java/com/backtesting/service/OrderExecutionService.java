package com.backtesting.service;

import com.backtesting.common.RequestContext;
import com.backtesting.events.EventPublisher;
import com.backtesting.events.OutboxRecorder;
import com.backtesting.events.TradeEvent;
import com.backtesting.model.OrderRequest;
import com.backtesting.model.OrderResult;
import com.backtesting.persistence.OrderRecordEntity;
import com.backtesting.persistence.OrderRecordRepository;
import com.backtesting.service.kis.KisTradingService;
import com.backtesting.service.metrics.TradingMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 주문 실행 + 영속화 + 이벤트 발행 조정자. 2-phase 기록 패턴.
 *
 * 생명주기:
 *   1) PLACING  — KIS 호출 전. 트랜잭션 #1 으로 OrderRecord INSERT + 감사 로그.
 *   2) ACCEPTED — KIS 가 주문번호 발급. 트랜잭션 #2 로 UPDATE + outbox 이벤트.
 *      REJECTED — KIS 거부 (주문번호는 없음, reject reason 기록).
 *      FAILED   — HTTP 타임아웃/예외. KIS 처리 여부 모호함 (운영자 수동 조회 필요).
 *
 * 불변식:
 *   - PLACING 상태 레코드는 항상 해소되어야 한다. 스케줄 잡이 5분 이상 된 PLACING 을 reconcile.
 *   - OrderRecord INSERT 와 outbox INSERT 는 같은 트랜잭션 — dual-write 제거.
 *   - idempotencyKey 가 있으면 DB UNIQUE 로 dedup.
 *
 * 실패 시나리오:
 *   (a) phase 1 commit 실패 → 클라이언트 5xx. KIS 에는 아무 요청도 안 갔음.
 *   (b) KIS HTTP 실패 (네트워크/타임아웃) → phase 2 에 FAILED 기록. 운영자 조회 필요.
 *   (c) KIS 성공 but phase 2 commit 실패 → 기록 PLACING 으로 남음. reconcile job 이 KIS 조회 후 보정.
 *
 * NOTE: phase 1 + phase 2 는 별도 트랜잭션. REQUIRED 기본 propagation 이지만,
 *   서비스 외부에서 @Transactional 로 감싼 호출은 금지 (복수 상태를 한 tx 에 섞지 않도록).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private static final String AUDIT_LEVEL_INFO = "INFO";
    private static final String AUDIT_LEVEL_WARN = "WARN";
    private static final String AUDIT_LEVEL_ERROR = "ERROR";

    private final KisTradingService kisTrading;
    private final OrderRecordRepository orderRepo;
    private final OutboxRecorder outbox;
    private final AuditLogService audit;
    private final Clock clock;
    private final TradingMetrics metrics;

    /**
     * @param req 주문 요청 (DTO 검증은 controller 에서 완료 전제)
     * @param idempotencyKey 있으면 OrderRecord 에 저장 — 동일 key 재요청 시 DB UNIQUE 로 감지
     * @return OrderResult — KIS 응답 원형 + success 플래그
     */
    public OrderResult execute(OrderRequest req, String idempotencyKey) {
        String recordId = UUID.randomUUID().toString();
        String principal = currentPrincipal();
        String traceId = currentTraceId();
        Instant startedAt = clock.instant();

        try (var ignored = MDC.putCloseable("orderRecordId", recordId)) {
            metrics.onSubmit(req.getMarket().name());

            // phase 1: PLACING 기록 (별도 tx) — crash-safe 흔적.
            recordPlacing(recordId, req, idempotencyKey, principal, traceId, startedAt);
            writeAudit(recordId, req, "ORDER_PLACING", AUDIT_LEVEL_INFO, principal, traceId,
                    "PLACING registered, calling KIS", null, 0);

            // KIS 호출 — 트랜잭션 밖 (외부 I/O).
            long t0 = System.nanoTime();
            OrderResult result;
            try {
                result = kisTrading.placeOrder(req);
            } catch (RuntimeException ex) {
                long latencyMs = elapsedMs(t0);
                markFailed(recordId, ex, latencyMs);
                writeAudit(recordId, req, "ORDER_FAILED", AUDIT_LEVEL_ERROR, principal, traceId,
                        "KIS call threw: " + ex.getClass().getSimpleName(), ex.getMessage(), latencyMs);
                metrics.onFailed(req.getMarket().name(), req.getSide(), Duration.ofMillis(latencyMs));
                throw ex;
            }

            long latencyMs = elapsedMs(t0);

            // phase 2: ACCEPTED / REJECTED (별도 tx) + outbox 이벤트.
            if (result.isSuccess()) {
                markAccepted(recordId, req, result, latencyMs);
                writeAudit(recordId, req, "ORDER_ACCEPTED", AUDIT_LEVEL_INFO, principal, traceId,
                        "KIS accepted orderNo=" + result.getOrderNo(), null, latencyMs);
                metrics.onAccepted(req.getMarket().name(), req.getSide(), Duration.ofMillis(latencyMs));
            } else {
                markRejected(recordId, req, result, latencyMs);
                writeAudit(recordId, req, "ORDER_REJECTED", AUDIT_LEVEL_WARN, principal, traceId,
                        "KIS rejected: " + result.getRawCode(), result.getMessage(), latencyMs);
                metrics.onRejected(req.getMarket().name(), req.getSide(),
                        safeReason(result), Duration.ofMillis(latencyMs));
            }

            return result;
        }
    }

    private static String safeReason(OrderResult r) {
        // rawCode 는 KIS 의 구조화 코드 (카디널리티 제한됨). 없으면 GENERIC_REJECT.
        return r.getRawCode() == null || r.getRawCode().isBlank() ? "GENERIC_REJECT" : r.getRawCode();
    }

    // ------------------ transactional phases ------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordPlacing(String id, OrderRequest req, String idempotencyKey,
                        String principal, String traceId, Instant now) {
        OrderRecordEntity rec = new OrderRecordEntity();
        rec.setId(id);
        rec.setMarket(req.getMarket().name());
        rec.setSymbol(req.getSymbol());
        rec.setSide(req.getSide());
        rec.setOrderType(req.getOrderType());
        rec.setQuantity(req.getQuantity());
        rec.setPrice(parsePrice(req.getPrice()));
        rec.setStatus("PLACING");
        rec.setCreatedAt(now);
        rec.setPrincipal(principal);
        rec.setTraceId(traceId);
        rec.setIdempotencyKey(idempotencyKey);
        orderRepo.save(rec);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markAccepted(String id, OrderRequest req, OrderResult result, long latencyMs) {
        OrderRecordEntity rec = orderRepo.findById(id).orElseThrow(() ->
                new IllegalStateException("OrderRecord disappeared between phase 1 and 2: id=" + id));
        rec.setStatus("ACCEPTED");
        rec.setOrderNo(result.getOrderNo());
        rec.setPlacedAt(clock.instant());
        rec.setLatencyMs(latencyMs);
        orderRepo.save(rec);

        TradeEvent event = buildEvent(req, result, "ORDER_PLACED", true);
        outbox.record("ORDER", rec.getId(), event.getEventType(),
                EventPublisher.TOPIC_TRADE, req.getSymbol(), event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markRejected(String id, OrderRequest req, OrderResult result, long latencyMs) {
        OrderRecordEntity rec = orderRepo.findById(id).orElseThrow(() ->
                new IllegalStateException("OrderRecord disappeared between phase 1 and 2: id=" + id));
        rec.setStatus("REJECTED");
        rec.setOrderNo(result.getOrderNo());
        rec.setPlacedAt(clock.instant());
        rec.setLatencyMs(latencyMs);
        rec.setRejectReason(truncate(result.getMessage(), 500));
        orderRepo.save(rec);

        TradeEvent event = buildEvent(req, result, "ORDER_REJECTED", false);
        outbox.record("ORDER", rec.getId(), event.getEventType(),
                EventPublisher.TOPIC_TRADE, req.getSymbol(), event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markFailed(String id, Throwable ex, long latencyMs) {
        OrderRecordEntity rec = orderRepo.findById(id).orElse(null);
        if (rec == null) {
            log.error("markFailed: record missing id={}", id);
            return;
        }
        rec.setStatus("FAILED");
        rec.setPlacedAt(clock.instant());
        rec.setLatencyMs(latencyMs);
        rec.setRejectReason(truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 500));
        orderRepo.save(rec);
        // FAILED 는 outbox 이벤트를 발행하지 않는다 — KIS 처리 여부가 모호하므로 downstream 에 ACCEPT 암시 금지.
    }

    // ------------------ helpers ------------------

    private void writeAudit(String recordId, OrderRequest req, String action, String level,
                             String principal, String traceId,
                             String detail, String error, long latencyMs) {
        // AuditLogService 는 자체 트랜잭션 사용 — 주문 트랜잭션과 분리.
        String path = "/api/trading/order";
        String body = String.format("{\"orderRecordId\":\"%s\",\"market\":\"%s\",\"symbol\":\"%s\","
                        + "\"side\":\"%s\",\"qty\":%d,\"traceId\":\"%s\"}",
                recordId, req.getMarket().name(), req.getSymbol(),
                req.getSide(), req.getQuantity(), traceId);
        int status = AUDIT_LEVEL_ERROR.equals(level) ? 500 : AUDIT_LEVEL_WARN.equals(level) ? 400 : 200;
        audit.record("POST", path + "#" + action, principal, body, status, latencyMs,
                error != null ? error : detail);
    }

    private TradeEvent buildEvent(OrderRequest req, OrderResult result, String eventType, boolean success) {
        return TradeEvent.builder()
                .schemaVersion(1)
                .eventType(eventType)
                .occurredAt(LocalDateTime.now())
                .source("OrderExecutionService")
                .symbol(req.getSymbol())
                .side(req.getSide())
                .quantity(req.getQuantity())
                .price(parsePrice(req.getPrice()))
                .orderNo(result.getOrderNo())
                .success(success)
                .message(result.getMessage())
                .build();
    }

    private static BigDecimal parsePrice(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String currentPrincipal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() ? a.getName() : "anonymous";
    }

    private static String currentTraceId() {
        RequestContext ctx = RequestContext.current();
        return ctx != null ? ctx.traceId() : null;
    }
}

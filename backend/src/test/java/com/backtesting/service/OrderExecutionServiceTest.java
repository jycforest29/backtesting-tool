package com.backtesting.service;

import com.backtesting.events.OutboxRecorder;
import com.backtesting.model.AssetType;
import com.backtesting.model.OrderRequest;
import com.backtesting.model.OrderResult;
import com.backtesting.persistence.OrderRecordEntity;
import com.backtesting.persistence.OrderRecordRepository;
import com.backtesting.service.kis.KisTradingService;
import com.backtesting.service.metrics.TradingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2-phase 주문 실행 로직 — 성공/거부/예외 3가지 경로에서 DB 상태 전이를 검증.
 *
 * 실제 JPA 대신 Map 기반 fake Repository 로 단순화. 핵심 행동 검증:
 *   - PLACING 이 먼저 기록된다
 *   - KIS 호출 후에만 최종 상태로 전이
 *   - FAILED 는 outbox 이벤트를 발행하지 않는다 (at-least-once 의 "false positive" 방지)
 */
class OrderExecutionServiceTest {

    private KisTradingService kisTrading;
    private OrderRecordRepository orderRepo;
    private OutboxRecorder outbox;
    private AuditLogService audit;
    private Clock clock;
    private TradingMetrics metrics;
    private SimpleMeterRegistry meters;
    private OrderExecutionService svc;

    private final Map<String, OrderRecordEntity> store = new HashMap<>();
    /**
     * 각 save() 시점의 엔티티 스냅샷. 같은 엔티티를 이후 mutation 해서 재저장해도
     * 스냅샷 리스트에는 당시 상태가 보존된다.
     */
    private final List<OrderRecordEntity> saveHistory = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kisTrading = mock(KisTradingService.class);
        orderRepo = mock(OrderRecordRepository.class);
        outbox = mock(OutboxRecorder.class);
        audit = mock(AuditLogService.class);
        clock = Clock.fixed(Instant.parse("2026-04-24T09:00:00Z"), ZoneId.of("UTC"));
        store.clear();
        saveHistory.clear();

        // fake repo: save()/findById() 를 in-memory map 으로. save() 시점에 shallow copy 를 히스토리에 보관.
        when(orderRepo.save(any(OrderRecordEntity.class))).thenAnswer(inv -> {
            OrderRecordEntity e = inv.getArgument(0);
            saveHistory.add(snapshot(e));
            store.put(e.getId(), e);
            return e;
        });
        when(orderRepo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0, String.class))));

        meters = new SimpleMeterRegistry();
        metrics = new TradingMetrics(meters);
        svc = new OrderExecutionService(kisTrading, orderRepo, outbox, audit, clock, metrics);
    }

    private static OrderRecordEntity snapshot(OrderRecordEntity src) {
        OrderRecordEntity copy = new OrderRecordEntity();
        copy.setId(src.getId());
        copy.setOrderNo(src.getOrderNo());
        copy.setMarket(src.getMarket());
        copy.setSymbol(src.getSymbol());
        copy.setSide(src.getSide());
        copy.setOrderType(src.getOrderType());
        copy.setQuantity(src.getQuantity());
        copy.setPrice(src.getPrice());
        copy.setStatus(src.getStatus());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setPlacedAt(src.getPlacedAt());
        copy.setLatencyMs(src.getLatencyMs());
        copy.setPrincipal(src.getPrincipal());
        copy.setTraceId(src.getTraceId());
        copy.setIdempotencyKey(src.getIdempotencyKey());
        copy.setRejectReason(src.getRejectReason());
        return copy;
    }

    @Test
    void acceptedPathWritesTwoSavesAndOneOutboxEvent() {
        when(kisTrading.placeOrder(any())).thenReturn(
                OrderResult.builder().success(true).orderNo("KIS-123").orderTime("090001").build());

        OrderResult result = svc.execute(sampleBuy(), "idem-1");

        assertThat(result.isSuccess()).isTrue();
        // 2번 save: PLACING 과 ACCEPTED.
        verify(orderRepo, times(2)).save(any());
        // 최종 상태.
        OrderRecordEntity stored = store.values().iterator().next();
        assertThat(stored.getStatus()).isEqualTo("ACCEPTED");
        assertThat(stored.getOrderNo()).isEqualTo("KIS-123");
        assertThat(stored.getPlacedAt()).isNotNull();
        assertThat(stored.getIdempotencyKey()).isEqualTo("idem-1");
        // outbox 이벤트 1건.
        verify(outbox, times(1))
                .record(eq("ORDER"), anyString(), eq("ORDER_PLACED"),
                        anyString(), eq("005930"), any());
        // 감사로그 2건: PLACING, ACCEPTED.
        verify(audit, times(2)).record(anyString(), anyString(), any(), anyString(),
                anyInt(), anyLong(), any());
    }

    @Test
    void rejectedPathStoresRejectReasonAndEmitsRejectedEvent() {
        when(kisTrading.placeOrder(any())).thenReturn(
                OrderResult.builder().success(false).message("잔고 부족").rawCode("KIS-ERR-01").build());

        OrderResult result = svc.execute(sampleBuy(), null);

        assertThat(result.isSuccess()).isFalse();
        OrderRecordEntity stored = store.values().iterator().next();
        assertThat(stored.getStatus()).isEqualTo("REJECTED");
        assertThat(stored.getRejectReason()).contains("잔고 부족");
        verify(outbox, times(1))
                .record(eq("ORDER"), anyString(), eq("ORDER_REJECTED"),
                        anyString(), eq("005930"), any());
    }

    @Test
    void kisExceptionMarksFailedAndSkipsOutbox() {
        when(kisTrading.placeOrder(any())).thenThrow(new RuntimeException("network blip"));

        assertThatThrownBy(() -> svc.execute(sampleBuy(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("network blip");

        OrderRecordEntity stored = store.values().iterator().next();
        assertThat(stored.getStatus()).isEqualTo("FAILED");
        assertThat(stored.getRejectReason()).contains("network blip");
        // FAILED 는 outbox 이벤트 없음 — KIS 처리 여부 모호.
        verifyNoInteractions(outbox);
    }

    @Test
    void placingRecordIsCreatedBeforeKisCall() {
        // KIS 호출 전에 이미 PLACING 레코드가 있어야 한다 (recoverable trail).
        when(kisTrading.placeOrder(any())).thenAnswer(inv -> {
            assertThat(store).hasSize(1);
            assertThat(store.values().iterator().next().getStatus()).isEqualTo("PLACING");
            return OrderResult.builder().success(true).orderNo("KIS-X").build();
        });

        svc.execute(sampleBuy(), null);
        // 최종 상태는 ACCEPTED.
        assertThat(store.values().iterator().next().getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void metricsReflectAcceptedRejectedFailedPaths() {
        when(kisTrading.placeOrder(any()))
                .thenReturn(OrderResult.builder().success(true).orderNo("KIS-1").build())
                .thenReturn(OrderResult.builder().success(false).rawCode("OVER_LIMIT").message("한도 초과").build())
                .thenThrow(new RuntimeException("network"));

        svc.execute(sampleBuy(), null);
        svc.execute(sampleBuy(), null);
        try { svc.execute(sampleBuy(), null); } catch (RuntimeException ignored) {}

        assertThat(meters.counter("trading_orders_submitted_total").count()).isEqualTo(3.0);
        assertThat(meters.counter("trading_orders_accepted_total").count()).isEqualTo(1.0);
        assertThat(meters.counter("trading_orders_rejected_total").count()).isEqualTo(1.0);
        assertThat(meters.counter("trading_orders_failed_total").count()).isEqualTo(1.0);
    }

    @Test
    void placingRecordCapturesRequestDetails() {
        when(kisTrading.placeOrder(any())).thenReturn(
                OrderResult.builder().success(true).orderNo("KIS-Y").build());

        svc.execute(sampleBuy(), "idem-xyz");

        // 첫 번째 save 스냅샷 = PLACING 상태의 요청 필드 전부 매핑되어 있어야 한다.
        assertThat(saveHistory).hasSizeGreaterThanOrEqualTo(2);
        OrderRecordEntity first = saveHistory.get(0);
        assertThat(first.getStatus()).isEqualTo("PLACING");
        assertThat(first.getMarket()).isEqualTo("KR_STOCK");
        assertThat(first.getSymbol()).isEqualTo("005930");
        assertThat(first.getSide()).isEqualTo("BUY");
        assertThat(first.getOrderType()).isEqualTo("LIMIT");
        assertThat(first.getQuantity()).isEqualTo(10);
        assertThat(first.getPrice()).isEqualTo(new BigDecimal("70000"));
        assertThat(first.getIdempotencyKey()).isEqualTo("idem-xyz");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getPlacedAt()).isNull();  // PLACING 상태에서는 아직 설정 안됨
    }

    private static OrderRequest sampleBuy() {
        OrderRequest r = new OrderRequest();
        r.setMarket(AssetType.KR_STOCK);
        r.setSymbol("005930");
        r.setSide("BUY");
        r.setOrderType("LIMIT");
        r.setQuantity(10);
        r.setPrice("70000");
        return r;
    }
}

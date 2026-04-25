package com.backtesting.persistence;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 실행 내역. 2-phase 기록:
 *   phase 1 (PLACING) — KIS 호출 전 초기 기록 (recoverable 흔적 남김)
 *   phase 2 (ACCEPTED / REJECTED / FAILED) — KIS 응답 반영 + outbox 이벤트
 *
 * FAILED 는 "HTTP 에러 등으로 KIS 처리 여부 알 수 없음" — 운영자가 KIS 조회로 수동 복구.
 * PLACING 상태로 5분 이상 머물러 있는 레코드는 스케줄 job 이 reconcile.
 */
@Entity
@Table(name = "order_record", indexes = {
        @Index(name = "ix_order_created_at", columnList = "createdAt"),
        @Index(name = "ix_order_status", columnList = "status"),
        @Index(name = "ix_order_idempotency", columnList = "idempotencyKey")
})
@Data
public class OrderRecordEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** KIS 에서 받은 주문번호 (성공 시). */
    private String orderNo;

    @Column(nullable = false, length = 16)
    private String market;      // KR_STOCK 등

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(length = 8)
    private String side;        // BUY / SELL

    @Column(length = 16)
    private String orderType;   // LIMIT / MARKET

    private long quantity;

    @Column(precision = 18, scale = 4)
    private BigDecimal price;

    /** PLACING / SENT / ACCEPTED / REJECTED / FAILED */
    @Column(nullable = false, length = 16)
    private String status;

    /** PLACING 최초 기록 시각. */
    @Column(nullable = false)
    private Instant createdAt;

    /** 최종 상태 (ACCEPTED/REJECTED/FAILED) commit 시각. placing 중에는 null. */
    private Instant placedAt;

    /** KIS HTTP 응답까지의 왕복 시간. */
    private Long latencyMs;

    @Column(length = 64)
    private String principal;

    @Column(length = 32)
    private String traceId;

    /** 있으면 링크. idempotent replay 조회용. */
    @Column(length = 128)
    private String idempotencyKey;

    @Column(length = 500)
    private String rejectReason;
}

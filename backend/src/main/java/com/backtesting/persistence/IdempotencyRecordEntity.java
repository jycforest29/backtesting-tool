package com.backtesting.persistence;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Client-supplied Idempotency-Key 기반 요청 중복 제거.
 *
 * Status:
 *   IN_FLIGHT — 첫 요청이 처리 중. 같은 키의 병렬 요청은 409.
 *   COMPLETED — 처리 완료. 같은 키+같은 request hash → 캐시된 응답 반환.
 *                                   같은 키+다른 request hash → 409 (키 재사용 오류).
 *   FAILED    — 처리 중 실패. 같은 키 재시도 허용 (retry-safe 한 실패일 수 있음).
 *
 * expires_at 경과 시 purge 배치가 삭제 — 키 재사용 가능.
 */
@Entity
@Table(name = "idempotency_record",
       uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key"),
       indexes = @Index(name = "ix_idempotency_expires", columnList = "expires_at"))
@Data
public class IdempotencyRecordEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String key;

    /** canonical 한 요청 body 의 SHA-256 hex. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Lob
    private String responsePayload;

    private Integer responseStatus;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(length = 500)
    private String errorMessage;
}

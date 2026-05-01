package com.backtesting.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Tamper-evident audit log (hash chain).
 *
 * 각 row 는 직전 row 의 hash 를 품고 있어 체인 형태.
 *  thisHash = SHA-256(seq | occurredAt | actor | action | payload | prevHash)
 *
 * 누군가 DB 를 직접 UPDATE / DELETE 해도 chain 이 깨져 verifier 가 탐지.
 * 금융 규제(MiFID II 감사, 국내 금감원 내부통제) 대응.
 *
 * 불변식:
 *  - seq 는 1 부터 단조증가, 빈틈 없음
 *  - prev_hash 는 이전 row 의 this_hash 와 동일 (첫 row 는 "GENESIS")
 */
@Entity
@Table(name = "audit_chain",
       indexes = @Index(name = "ix_audit_chain_occurred_at", columnList = "occurred_at"))
@Getter
@Setter
@ToString(of = {"seq", "actor", "action"})
public class AuditChainEntity extends AbstractEntity<Long> {

    @Id
    private Long seq;                   // 전역 단조증가 — 체인 순서

    @Override
    public Long getId() { return seq; }

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Lob
    @Column(nullable = false)
    private String payload;             // JSON canonical

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;            // hex SHA-256

    @Column(name = "this_hash", nullable = false, length = 64)
    private String thisHash;

    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";
}

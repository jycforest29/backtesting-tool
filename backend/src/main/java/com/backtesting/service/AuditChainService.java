package com.backtesting.service;

import com.backtesting.persistence.AuditChainEntity;
import com.backtesting.persistence.AuditChainRepository;
import com.backtesting.persistence.JsonConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Tamper-evident audit writer.
 *
 *  thisHash = SHA-256(seq | occurredAt | actor | action | payload | prevHash)
 *
 * 쓰기는 단일 @Transactional — 두 스레드가 동시에 append 시도해도 DB 의 PK(seq) 가
 * 경쟁조건을 차단하거나 직렬화 예외로 한 쪽만 성공. 체인 무결성이 DB 레벨에서 보장.
 *
 * 이 구현은 simplicity 위주. 고빈도 append 필요 시 sequence 할당을 별도 전략으로 교체
 * (예: in-memory counter + @SchedulerLock) — 여기선 DB round-trip 을 감수.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainService {

    private final AuditChainRepository repo;
    private final Clock clock;
    private final ObjectMapper mapper = JsonConverter.mapper();

    /** 새 row append. 직렬화된 payload 는 canonical JSON. 같은 tx 에서 head 조회 + INSERT. */
    @Transactional
    public AuditChainEntity append(String actor, String action, Object payload) {
        AuditChainEntity head = repo.findTopByOrderBySeqDesc().orElse(null);
        long nextSeq = head == null ? 1L : head.getSeq() + 1;
        String prevHash = head == null ? AuditChainEntity.GENESIS_HASH : head.getThisHash();

        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("audit payload serialize failed: " + e.getMessage(), e);
        }

        Instant now = clock.instant();
        String thisHash = hash(nextSeq, now, actor, action, payloadJson, prevHash);

        AuditChainEntity row = new AuditChainEntity();
        row.setSeq(nextSeq);
        row.setOccurredAt(now);
        row.setActor(actor);
        row.setAction(action);
        row.setPayload(payloadJson);
        row.setPrevHash(prevHash);
        row.setThisHash(thisHash);
        return repo.save(row);
    }

    /** 특정 row 의 해시를 재계산 — verifier 에서 사용. */
    public String recomputeHash(AuditChainEntity row) {
        return hash(row.getSeq(), row.getOccurredAt(), row.getActor(),
                row.getAction(), row.getPayload(), row.getPrevHash());
    }

    /**
     * Canonical SHA-256 — 완전 결정론.
     * Instant.toString() 은 locale/JDK 버전에 따라 포맷이 미묘하게 달라질 수 있어 금지.
     * 대신 epochSecond(big-endian 8 bytes) + nano(big-endian 4 bytes) + fixed-width seq 로 고정.
     * 모든 문자열은 UTF-8 길이 prefix + bytes 구조로 인코딩 — field 경계 불명확성 제거.
     */
    static String hash(long seq, Instant occurredAt, String actor, String action,
                       String payload, String prevHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(longBytes(seq));
            md.update(longBytes(occurredAt.getEpochSecond()));
            md.update(intBytes(occurredAt.getNano()));
            writeLengthPrefixed(md, actor);
            writeLengthPrefixed(md, action);
            writeLengthPrefixed(md, payload);
            writeLengthPrefixed(md, prevHash);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeLengthPrefixed(MessageDigest md, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        md.update(intBytes(bytes.length));
        md.update(bytes);
    }

    private static byte[] longBytes(long v) {
        return new byte[]{
                (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] intBytes(int v) {
        return new byte[]{
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
}

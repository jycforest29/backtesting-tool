package com.backtesting.service;

import com.backtesting.persistence.AuditChainEntity;
import com.backtesting.persistence.AuditChainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Audit chain 무결성 검증자.
 *
 * 체인 invariant:
 *   row(n).prevHash == row(n-1).thisHash
 *   row(n).thisHash == SHA-256(row(n) 필드들, row(n-1).thisHash)
 *   seq 는 1 부터 빈틈없이 증가
 *
 * 위 중 하나라도 어긋나면 tamper detected → log ERROR + 메트릭 노출.
 * 프로덕션에서는 운영 on-call 호출 로직이 이 이벤트를 pickup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainVerifier {

    private final AuditChainRepository repo;
    private final AuditChainService service;

    @Value("${audit.chain.verify-batch-size:500}")
    private int batchSize;

    public static class TamperDetected extends RuntimeException {
        public final long seq;
        public final String reason;
        public TamperDetected(long seq, String reason) {
            super("audit chain tampered at seq=" + seq + ": " + reason);
            this.seq = seq;
            this.reason = reason;
        }
    }

    /**
     * 전체 체인 스캔. 장애 시 즉시 TamperDetected.
     * 운영에선 @Scheduled 로 하루 1회, 데모/테스트에선 직접 호출.
     */
    public VerifyResult verifyAll() {
        long lastSeq = 0;
        String expectedPrevHash = AuditChainEntity.GENESIS_HASH;
        int verified = 0;

        while (true) {
            List<AuditChainEntity> batch = repo.findBySeqGreaterThanOrderBySeqAsc(
                    lastSeq, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) break;

            for (AuditChainEntity row : batch) {
                if (row.getSeq() != lastSeq + 1) {
                    throw new TamperDetected(row.getSeq(),
                            "seq gap: expected " + (lastSeq + 1) + " got " + row.getSeq());
                }
                if (!row.getPrevHash().equals(expectedPrevHash)) {
                    throw new TamperDetected(row.getSeq(),
                            "prevHash mismatch: expected " + expectedPrevHash
                                    + " got " + row.getPrevHash());
                }
                String recomputed = service.recomputeHash(row);
                if (!recomputed.equals(row.getThisHash())) {
                    throw new TamperDetected(row.getSeq(),
                            "thisHash mismatch: payload altered after write");
                }
                expectedPrevHash = row.getThisHash();
                lastSeq = row.getSeq();
                verified++;
            }
        }
        log.debug("Audit chain verified: {} rows OK", verified);
        return new VerifyResult(verified, lastSeq);
    }

    @Scheduled(cron = "${audit.chain.verify-cron:0 15 4 * * *}")
    public void scheduledVerify() {
        try {
            VerifyResult r = verifyAll();
            log.info("Audit chain scheduled verify: {} rows, head seq={}", r.verified(), r.lastSeq());
        } catch (TamperDetected e) {
            log.error("AUDIT CHAIN TAMPERED at seq={}: {}", e.seq, e.reason);
            // 실제 환경에선 PagerDuty/Opsgenie 연동
        }
    }

    public record VerifyResult(int verified, long lastSeq) {}
}

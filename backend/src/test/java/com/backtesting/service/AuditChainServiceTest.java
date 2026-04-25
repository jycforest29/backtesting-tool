package com.backtesting.service;

import com.backtesting.persistence.AuditChainEntity;
import com.backtesting.persistence.AuditChainRepository;
import com.backtesting.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hash-chained audit log — tamper evidence.
 *
 * 시나리오:
 *   1) 정상 append N 건 → 체인 verify 통과
 *   2) payload 를 직접 수정 → thisHash 불일치 → TamperDetected
 *   3) 중간 row 삭제 → seq gap → TamperDetected
 *   4) prevHash 조작 → 연결 끊김 → TamperDetected
 */
class AuditChainServiceTest {

    private List<AuditChainEntity> store;
    private AuditChainRepository repo;
    private AuditChainService service;
    private AuditChainVerifier verifier;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();
        repo = inMemory(store);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = new AuditChainService(repo, clock);
        verifier = new AuditChainVerifier(repo, service);
        org.springframework.test.util.ReflectionTestUtils.setField(verifier, "batchSize", 100);
    }

    @Test
    void appendedChain_verifiesClean() {
        for (int i = 0; i < 10; i++) {
            service.append("user-" + i, "PLACE_ORDER", "{\"idx\":" + i + "}");
            clock.advanceMs(1000);
        }
        AuditChainVerifier.VerifyResult r = verifier.verifyAll();
        assertEquals(10, r.verified());
        assertEquals(10L, r.lastSeq());
    }

    @Test
    void tamperedPayload_isDetected() {
        service.append("admin", "LOGIN", "{\"ok\":true}");
        service.append("admin", "ORDER", "{\"amount\":100}");

        // DB 직접 조작: 2번째 row 의 payload 변경 (thisHash 는 그대로 둠 → 불일치)
        store.get(1).setPayload("{\"amount\":999999}");

        AuditChainVerifier.TamperDetected ex = assertThrows(
                AuditChainVerifier.TamperDetected.class,
                () -> verifier.verifyAll());
        assertEquals(2L, ex.seq);
        assertTrue(ex.reason.contains("thisHash mismatch"));
    }

    @Test
    void droppedMiddleRow_isDetected() {
        service.append("u", "a", "{}");
        service.append("u", "b", "{}");
        service.append("u", "c", "{}");
        // 2번째 삭제
        store.removeIf(r -> r.getSeq() == 2L);

        AuditChainVerifier.TamperDetected ex = assertThrows(
                AuditChainVerifier.TamperDetected.class,
                () -> verifier.verifyAll());
        assertTrue(ex.reason.contains("seq gap"));
    }

    @Test
    void brokenPrevHashLink_isDetected() {
        service.append("u", "a", "{}");
        service.append("u", "b", "{}");
        store.get(1).setPrevHash("deadbeef".repeat(8)); // 임의 변조

        AuditChainVerifier.TamperDetected ex = assertThrows(
                AuditChainVerifier.TamperDetected.class,
                () -> verifier.verifyAll());
        assertTrue(ex.reason.contains("prevHash mismatch") || ex.reason.contains("thisHash mismatch"));
    }

    @Test
    void genesisRow_usesGenesisHash() {
        AuditChainEntity row = service.append("sys", "BOOT", "{}");
        assertEquals(1L, row.getSeq());
        assertEquals(AuditChainEntity.GENESIS_HASH, row.getPrevHash());
    }

    // ----- helpers -----

    private AuditChainRepository inMemory(List<AuditChainEntity> store) {
        AuditChainRepository mock = mock(AuditChainRepository.class);
        when(mock.findTopByOrderBySeqDesc()).thenAnswer(inv ->
                store.stream().max(Comparator.comparingLong(AuditChainEntity::getSeq)));
        when(mock.findBySeqGreaterThanOrderBySeqAsc(anyLong(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    long after = inv.getArgument(0);
                    Pageable page = inv.getArgument(1);
                    return store.stream()
                            .filter(r -> r.getSeq() > after)
                            .sorted(Comparator.comparingLong(AuditChainEntity::getSeq))
                            .limit(page.getPageSize())
                            .collect(Collectors.toList());
                });
        doAnswer(inv -> {
            AuditChainEntity r = inv.getArgument(0);
            store.add(r);
            return r;
        }).when(mock).save(any());
        when(mock.findById(any())).thenAnswer(inv -> {
            long seq = inv.getArgument(0);
            return store.stream().filter(r -> r.getSeq() == seq).findFirst();
        });
        return mock;
    }
}

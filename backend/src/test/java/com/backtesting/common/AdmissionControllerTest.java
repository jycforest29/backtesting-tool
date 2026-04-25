package com.backtesting.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Priority 기반 admission control 동작 검증.
 *  - softLimit 초과 → BULK 거부, STANDARD/CRITICAL 통과
 *  - hardLimit 초과 → CRITICAL 만 통과
 */
class AdmissionControllerTest {

    private AdmissionController ac;

    @BeforeEach
    void setUp() {
        ac = new AdmissionController();
        ReflectionTestUtils.setField(ac, "softLimit", 4);
        ReflectionTestUtils.setField(ac, "hardLimit", 6);
    }

    @Test
    void belowSoftLimit_allPrioritiesPass() {
        for (int i = 0; i < 3; i++) {
            assertTrue(ac.tryAcquire(Priority.BULK));
        }
        assertEquals(3, ac.currentInFlight());
    }

    @Test
    void atSoftLimit_bulkRejected_standardAndCriticalPass() {
        // fill to softLimit with STANDARD
        for (int i = 0; i < 4; i++) assertTrue(ac.tryAcquire(Priority.STANDARD));
        assertEquals(4, ac.currentInFlight());

        assertFalse(ac.tryAcquire(Priority.BULK), "softLimit 초과 시 BULK 거부");
        assertTrue(ac.tryAcquire(Priority.STANDARD), "STANDARD 는 아직 통과");
        assertTrue(ac.tryAcquire(Priority.CRITICAL), "CRITICAL 은 항상 통과");
    }

    @Test
    void atHardLimit_onlyCriticalPasses() {
        for (int i = 0; i < 6; i++) ac.tryAcquire(Priority.STANDARD);
        assertEquals(6, ac.currentInFlight());

        assertFalse(ac.tryAcquire(Priority.BULK));
        assertFalse(ac.tryAcquire(Priority.STANDARD));
        assertTrue(ac.tryAcquire(Priority.CRITICAL),
                "hardLimit 초과 시 CRITICAL 만 통과 (주문·청산 보호)");
    }

    @Test
    void release_freesSlot() {
        ac.tryAcquire(Priority.STANDARD);
        ac.tryAcquire(Priority.STANDARD);
        assertEquals(2, ac.currentInFlight());
        ac.release();
        assertEquals(1, ac.currentInFlight());
    }

    @Test
    void priorityParseFallback() {
        assertEquals(Priority.STANDARD, Priority.parse(null));
        assertEquals(Priority.STANDARD, Priority.parse("NONSENSE"));
        assertEquals(Priority.CRITICAL, Priority.parse("CRITICAL"));
        assertEquals(Priority.BULK, Priority.parse("bulk"));
    }
}

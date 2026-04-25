package com.backtesting.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Priority 기반 admission control + adaptive concurrency limit.
 *
 * 설계:
 *  - 우선순위별 in-flight 카운터
 *  - 시스템 전체 in-flight 가 softLimit 초과 → BULK 거부
 *  - hardLimit 초과 → STANDARD 까지 거부, CRITICAL 만 통과
 *  - 초과 거부 시 HTTP 503 + Retry-After 힌트
 *
 * Netflix concurrency-limits 의 축약 버전. 외부 의존 없이 순수 Java.
 * 실제 "adaptive" 는 p99 latency 기반 리미트 조정까지 가야 완전체 — 여기선 정적 임계치.
 */
@Slf4j
@Component
public class AdmissionController {

    @Value("${admission.soft-limit:32}")
    private int softLimit;

    @Value("${admission.hard-limit:48}")
    private int hardLimit;

    private final AtomicInteger inFlight = new AtomicInteger(0);

    /** 요청 시작. true=진입 허가, false=거부. permit() 이 true 면 반드시 release() 호출. */
    public boolean tryAcquire(Priority p) {
        int cur = inFlight.get();
        if (cur >= hardLimit) {
            return p == Priority.CRITICAL && inFlight.incrementAndGet() > 0;
        }
        if (cur >= softLimit && p == Priority.BULK) {
            return false;
        }
        inFlight.incrementAndGet();
        return true;
    }

    public void release() {
        inFlight.decrementAndGet();
    }

    public int currentInFlight() {
        return inFlight.get();
    }

    public int softLimit() { return softLimit; }
    public int hardLimit() { return hardLimit; }
}

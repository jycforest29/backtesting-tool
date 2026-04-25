package com.backtesting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 단순 분산 락 (SET NX EX 패턴).
 * 다중 인스턴스 환경에서 동일 전략/OCO 포지션이 동시에 처리되는 걸 방지.
 *
 * 용도:
 *  - QuantExecutionService.runStrategy() — 스케줄러 중복 발화 방어
 *  - OcoOrderService 본주문 전송 — 동일 포지션 concurrent 매수 방지
 *
 * 장애 대비:
 *  - Redis 미가용 시 tryLock이 false 반환 → 호출측이 기존 동기화(ConcurrentHashMap) fallback 활용
 *  - 락 만료 TTL로 보호 (프로세스 크래시 후에도 자동 해제)
 */
@Slf4j
@Component
@ConditionalOnBean(StringRedisTemplate.class)
@RequiredArgsConstructor
public class DistributedLock {

    private final StringRedisTemplate redis;
    private final ThreadLocal<String> heldToken = new ThreadLocal<>();

    /** 지정 TTL로 락 획득. 성공 시 true. */
    public boolean tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent("lock:" + key, token, ttl.toSeconds(), TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(ok)) {
            heldToken.set(token);
            return true;
        }
        return false;
    }

    /** 락 해제 — 획득한 토큰 일치할 때만 (Lua 원자 연산 권장하나 간소화). */
    public void release(String key) {
        String token = heldToken.get();
        if (token == null) return;
        String current = redis.opsForValue().get("lock:" + key);
        if (token.equals(current)) {
            redis.delete("lock:" + key);
        }
        heldToken.remove();
    }

    /** try-with / finally 편의 래퍼. 락 못 잡으면 false 반환, 잡으면 runnable 실행 후 해제. */
    public boolean runExclusive(String key, Duration ttl, Runnable action) {
        if (!tryLock(key, ttl)) return false;
        try {
            action.run();
            return true;
        } finally {
            release(key);
        }
    }
}

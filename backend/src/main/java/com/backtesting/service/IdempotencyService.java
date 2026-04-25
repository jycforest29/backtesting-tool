package com.backtesting.service;

import com.backtesting.persistence.IdempotencyRecordEntity;
import com.backtesting.persistence.IdempotencyRecordRepository;
import com.backtesting.persistence.JsonConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client-supplied X-Idempotency-Key 기반 요청 중복 제거.
 *
 * 프로토콜 (Stripe 등 호환):
 *  1) 신규 키 → DB INSERT (UNIQUE) → 성공하면 operation 실행 → 응답 캐시 + COMPLETED
 *  2) 이미 존재하는 키:
 *     - hash 일치 + COMPLETED → 캐시된 응답 반환 (replay=true)
 *     - hash 불일치 → 409 IdempotencyConflict (같은 키로 다른 body)
 *     - IN_FLIGHT → 409 IdempotencyInFlight (동시 처리 중)
 *
 * 주의:
 *  - operation 실행은 @Transactional 바깥 (외부 HTTP 포함). 내부에서 트랜잭션 필요 시
 *    operation supplier 가 자체적으로 @Transactional 을 걸 것.
 *  - INSERT 단계만 짧은 tx 로 수행. DB UNIQUE 제약이 경쟁 조건을 차단.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository repo;
    private final Clock clock;
    private final ObjectMapper mapper = JsonConverter.mapper();

    /** 새 키 예약 or 기존 키 조회. null 반환 시 새 key 예약 성공, operation 진행 가능. */
    @Transactional
    Optional<IdempotencyRecordEntity> reserve(String key, String requestHash) {
        IdempotencyRecordEntity e = new IdempotencyRecordEntity();
        e.setId(UUID.randomUUID().toString());
        e.setKey(key);
        e.setRequestHash(requestHash);
        e.setStatus("IN_FLIGHT");
        e.setCreatedAt(clock.instant());
        e.setExpiresAt(clock.instant().plus(TTL));
        try {
            repo.saveAndFlush(e);
            return Optional.empty();
        } catch (DataIntegrityViolationException dup) {
            return repo.findByKey(key);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markCompleted(String key, int status, Object response) {
        IdempotencyRecordEntity e = repo.findByKey(key).orElse(null);
        if (e == null) return;
        try {
            e.setStatus("COMPLETED");
            e.setResponseStatus(status);
            e.setResponsePayload(mapper.writeValueAsString(response));
            repo.save(e);
        } catch (Exception ex) {
            log.warn("Idempotency markCompleted failed key={}: {}", key, ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markFailed(String key, String error) {
        IdempotencyRecordEntity e = repo.findByKey(key).orElse(null);
        if (e == null) return;
        e.setStatus("FAILED");
        e.setErrorMessage(truncate(error, 500));
        repo.save(e);
    }

    /**
     * 전체 실행 래퍼.
     * @param key client 에서 온 Idempotency-Key
     * @param requestBody 요청 body (해시 계산용)
     * @param responseType 응답 역직렬화 타입
     * @param operation 실제 실행 로직 (HTTP, DB write 등 포함 가능)
     */
    public <T> Result<T> execute(String key, Object requestBody,
                                  Class<T> responseType,
                                  Supplier<T> operation) {
        String hash = hashRequest(requestBody);
        Optional<IdempotencyRecordEntity> existing = reserve(key, hash);

        if (existing.isPresent()) {
            IdempotencyRecordEntity e = existing.get();
            if (!e.getRequestHash().equals(hash)) {
                throw new Conflict("Idempotency-Key 재사용됨 — request body 가 다름");
            }
            if ("IN_FLIGHT".equals(e.getStatus())) {
                throw new InFlight("같은 Idempotency-Key 요청이 처리 중");
            }
            if ("FAILED".equals(e.getStatus())) {
                // 실패는 재시도 허용 — 동일 키 재예약 불가하므로 예외 던짐 (client 가 새 키로 재시도)
                throw new Conflict("이전 요청 FAILED — 새로운 Idempotency-Key 로 재시도");
            }
            // COMPLETED + hash 일치 → 캐시 응답
            try {
                T cached = mapper.readValue(e.getResponsePayload(), responseType);
                return new Result<>(cached, true);
            } catch (Exception ex) {
                throw new IllegalStateException("캐시된 응답 역직렬화 실패: " + ex.getMessage(), ex);
            }
        }

        // 신규 실행
        try {
            T result = operation.get();
            markCompleted(key, 200, result);
            return new Result<>(result, false);
        } catch (RuntimeException ex) {
            markFailed(key, ex.getMessage());
            throw ex;
        }
    }

    /** TTL 경과된 행 제거. 매일 03:30 KST. */
    @Scheduled(cron = "${idempotency.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        long n = repo.deleteByExpiresAtBefore(clock.instant());
        if (n > 0) log.info("Idempotency purge: removed {} expired records", n);
    }

    private String hashRequest(Object body) {
        try {
            String canonical = mapper.writeValueAsString(body);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("request hash failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record Result<T>(T value, boolean replay) {}

    public static class Conflict extends RuntimeException {
        public Conflict(String m) { super(m); }
    }

    public static class InFlight extends RuntimeException {
        public InFlight(String m) { super(m); }
    }
}

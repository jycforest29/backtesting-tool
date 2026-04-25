package com.backtesting.events;

import com.backtesting.persistence.JsonConverter;
import com.backtesting.persistence.OutboxEventEntity;
import com.backtesting.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 폴링 publisher.
 *
 * 역할:
 *  - 주기적으로 unsent outbox 행을 조회
 *  - Kafka 로 동기 발행 (ack 대기)
 *  - 성공 → sent_at 마킹 (별도 tx), 실패 → retry_count++ + 지수 백오프
 *
 * 보장: at-least-once. 소비자는 event id 로 dedup 해야 한다.
 *
 * 크래시 내성:
 *  - Kafka 는 들어갔는데 DB 마킹 전 크래시 → 다음 run 에서 재발행 (중복, 소비자 dedup)
 *  - DB INSERT 후 Kafka 전에 크래시 → 다음 run 에서 정상 발행 (단순 지연)
 *
 * 다중 인스턴스 확장: 현재 쿼리는 SKIP LOCKED 미적용 → 단일 publisher 가정.
 * 분산 환경으로 가면 native query + SELECT FOR UPDATE SKIP LOCKED 로 교체.
 */
@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxEventRepository repo;
    private final Clock clock;
    private final ObjectMapper mapper = JsonConverter.mapper();

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    public OutboxPublisher(OutboxEventRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 테스트용 호환 생성자. */
    public OutboxPublisher(OutboxEventRepository repo) {
        this(repo, Clock.systemDefaultZone());
    }

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize;

    @Value("${outbox.publisher.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    /** 재시도 횟수별 다음 시도까지 대기(초). 마지막 값은 상한. */
    private static final long[] BACKOFF_SECONDS = {1, 5, 30, 120, 600, 3600};

    @Scheduled(fixedDelayString = "${outbox.publisher.interval-ms:2000}",
               initialDelayString = "${outbox.publisher.initial-delay-ms:5000}")
    public void drainBatch() {
        if (kafka == null) return; // Kafka 미구성 — 로컬 dev

        List<OutboxEventEntity> batch;
        try {
            batch = repo.findUnsent(clock.instant(), PageRequest.of(0, batchSize));
        } catch (Exception e) {
            log.warn("Outbox fetch failed: {}", e.getMessage());
            return;
        }
        if (batch.isEmpty()) return;

        log.debug("Outbox draining {} events", batch.size());
        for (OutboxEventEntity e : batch) {
            try {
                publishOne(e.getId());
            } catch (Exception ex) {
                log.warn("Outbox publishOne threw for {}: {}", e.getId(), ex.getMessage());
            }
        }
    }

    /**
     * 개별 이벤트 발행 — two-phase.
     *
     * Phase 1 (NO tx): findById + Kafka send — 네트워크 I/O 동안 DB 커넥션 점유 안 함.
     * Phase 2 (REQUIRES_NEW tx): 성공/실패에 따라 markSent / markRetry 각자 독립 tx.
     *
     * 왜 중요한가:
     *  - 하나의 @Transactional 로 묶으면 Kafka send 5초 대기 동안 DB 커넥션 holdup →
     *    Postgres 운영환경에서 connection pool 고갈 + 타 요청 waiting. H2 에선 안 보여도
     *    프로덕션 DB 에서 터짐. 빅테크 체크리스트 1번.
     *  - markSent/markRetry 를 REQUIRES_NEW 로 분리 → publishOne 호출부의 tx 컨텍스트와
     *    격리. OutboxPublisher 는 어디서 호출돼도 안전.
     */
    public void publishOne(String id) {
        OutboxEventEntity e = repo.findById(id).orElse(null);
        if (e == null || e.getSentAt() != null) return;

        try {
            Map<?, ?> payload = mapper.readValue(e.getPayload(), Map.class);
            kafka.send(e.getTopic(), e.getPartitionKey(), payload)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            markSent(id);
        } catch (Exception ex) {
            markRetry(id, ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markSent(String id) {
        OutboxEventEntity e = repo.findById(id).orElse(null);
        if (e == null || e.getSentAt() != null) return;
        e.setSentAt(clock.instant());
        e.setLastError(null);
        repo.save(e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markRetry(String id, Exception ex) {
        OutboxEventEntity e = repo.findById(id).orElse(null);
        if (e == null || e.getSentAt() != null) return;
        e.setRetryCount(e.getRetryCount() + 1);
        e.setLastError(truncate(ex.getMessage(), 500));
        long backoffSec = BACKOFF_SECONDS[
                Math.min(e.getRetryCount() - 1, BACKOFF_SECONDS.length - 1)];
        e.setNextAttemptAt(clock.instant().plusSeconds(backoffSec));
        repo.save(e);
        log.warn("Outbox publish failed id={} retry={} next={}s cause={}",
                id, e.getRetryCount(), backoffSec, ex.getMessage());
    }

    /** 발행 완료된 오래된 행 정리. 매일 04:00 KST. */
    @Scheduled(cron = "${outbox.publisher.purge-cron:0 0 4 * * *}")
    @Transactional
    public void purgeOld() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(7));
        long n = repo.deleteBySentAtIsNotNullAndSentAtBefore(cutoff);
        if (n > 0) log.info("Outbox purge: removed {} sent events older than {}", n, cutoff);
    }

    /** /actuator/health 같은 곳에서 재사용 가능한 backlog 수치. */
    public long backlog() {
        return repo.countBySentAtIsNull();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

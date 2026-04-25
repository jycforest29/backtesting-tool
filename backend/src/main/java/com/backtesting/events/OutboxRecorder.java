package com.backtesting.events;

import com.backtesting.persistence.JsonConverter;
import com.backtesting.persistence.OutboxEventEntity;
import com.backtesting.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 비즈니스 트랜잭션 내부에서 호출해 outbox 에 이벤트를 INSERT 한다.
 * 호출부가 @Transactional 이어야 정상 동작 (MANDATORY) — 실수로 tx 밖에서
 * 호출하면 예외 발생 → outbox 가 비즈니스 상태와 분리되는 참사 방지.
 */
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository repo;
    private final Clock clock;
    private final ObjectMapper mapper = JsonConverter.mapper();

    @Transactional(propagation = Propagation.MANDATORY)
    public String record(String aggregateType, String aggregateId, String eventType,
                         String topic, String partitionKey, Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            OutboxEventEntity e = new OutboxEventEntity();
            e.setId(UUID.randomUUID().toString());
            e.setAggregateType(aggregateType);
            e.setAggregateId(aggregateId);
            e.setEventType(eventType);
            e.setTopic(topic);
            e.setPartitionKey(partitionKey);
            e.setPayload(json);
            Instant now = clock.instant();
            e.setCreatedAt(now);
            e.setNextAttemptAt(now);
            repo.save(e);
            return e.getId();
        } catch (Exception ex) {
            throw new IllegalStateException("Outbox record failed: " + ex.getMessage(), ex);
        }
    }
}

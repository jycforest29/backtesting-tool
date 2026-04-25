package com.backtesting.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, String> {

    /**
     * 미발행 + 재시도 시점 도달한 이벤트를 createdAt 오름차순으로.
     * 다중 인스턴스 확장 시 JPQL → native SELECT FOR UPDATE SKIP LOCKED 로 교체 필요
     * (H2 는 SKIP LOCKED 미지원이라 지금은 단일 publisher 가정).
     */
    @Query("SELECT e FROM OutboxEventEntity e " +
           "WHERE e.sentAt IS NULL AND e.nextAttemptAt <= :now " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEventEntity> findUnsent(@Param("now") Instant now, Pageable page);

    long deleteBySentAtIsNotNullAndSentAtBefore(Instant cutoff);

    long countBySentAtIsNull();
}

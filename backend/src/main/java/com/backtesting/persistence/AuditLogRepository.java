package com.backtesting.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogRowEntity, Long> {

    List<AuditLogRowEntity> findAllByOrderByTimestampDesc(Pageable pageable);

    List<AuditLogRowEntity> findByLevelOrderByTimestampDesc(String level, Pageable pageable);

    List<AuditLogRowEntity> findByActionContainingIgnoreCaseOrderByTimestampDesc(String action, Pageable pageable);

    @Query("SELECT a.level AS level, COUNT(a) AS cnt FROM AuditLogRowEntity a GROUP BY a.level")
    List<LevelCount> countByLevel();

    long deleteByTimestampBefore(LocalDateTime threshold);

    interface LevelCount {
        String getLevel();
        long getCnt();
    }
}

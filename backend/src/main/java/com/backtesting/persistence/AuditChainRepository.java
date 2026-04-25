package com.backtesting.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditChainRepository extends JpaRepository<AuditChainEntity, Long> {

    Optional<AuditChainEntity> findTopByOrderBySeqDesc();

    List<AuditChainEntity> findBySeqGreaterThanOrderBySeqAsc(long afterSeq, Pageable page);
}

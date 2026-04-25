package com.backtesting.persistence;

import com.backtesting.model.OcoPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OcoPositionRepository extends JpaRepository<OcoPositionEntity, String> {

    /** 기동 시 복구해야 할 '살아있는' 포지션. 종결 상태 제외. */
    List<OcoPositionEntity> findByStatusIn(List<OcoPosition.OcoStatus> statuses);

    /** 종목·상태 기반 조회 — 중복 등록 방지 검증용. */
    long countBySymbolAndStatusIn(String symbol, List<OcoPosition.OcoStatus> statuses);
}

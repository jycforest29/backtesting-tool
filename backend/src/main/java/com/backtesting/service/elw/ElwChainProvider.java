package com.backtesting.service.elw;

import com.backtesting.model.elw.ElwModels.ElwContract;

import java.util.List;

/**
 * ELW 체인 데이터 소스 추상화 (KIS, 대체 소스 등).
 * 구현은 미구성 상태에서 빈 리스트·isAvailable()=false 로 응답, 예외로 가드하지 말 것.
 */
public interface ElwChainProvider {

    List<ElwContract> fetchChain(String underlyingCode) throws Exception;

    boolean isAvailable();

    String label();
}

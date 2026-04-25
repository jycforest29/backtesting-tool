package com.backtesting.common;

/**
 * 요청 우선순위. load shedding 단계의 기준.
 *
 *  CRITICAL — 주문·포지션 청산 등 돈 관련. 최후까지 살림.
 *  STANDARD — 조회·대시보드. 시스템 degraded 시 제한.
 *  BULK     — 백테스트·preview·팩터 스캔. 제일 먼저 버림.
 */
public enum Priority {
    CRITICAL, STANDARD, BULK;

    public static Priority parse(String s) {
        if (s == null) return STANDARD;
        try {
            return Priority.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}

package com.backtesting.service.quant;

import java.time.LocalDate;
import java.util.List;

/**
 * 리밸런싱 시점 판단 유틸. availableDates는 오름차순 거래일 리스트.
 */
final class RebalanceTiming {

    private RebalanceTiming() {}

    /** today가 자신이 속한 달의 "마지막 거래일"인지. availableDates는 정렬된 거래일. */
    static boolean isLastTradingDayOfMonth(LocalDate today, List<LocalDate> availableDates) {
        int idx = availableDates.indexOf(today);
        if (idx < 0 || idx == availableDates.size() - 1) {
            // 마지막 거래일 (데이터 끝)이면 false — 아직 다음 달 데이터가 없으니 판단 불가
            return false;
        }
        LocalDate next = availableDates.get(idx + 1);
        return next.getMonthValue() != today.getMonthValue() || next.getYear() != today.getYear();
    }

    /** 월초 리밸런싱 변형: today가 자신이 속한 달의 "첫 거래일"인지. */
    static boolean isFirstTradingDayOfMonth(LocalDate today, List<LocalDate> availableDates) {
        int idx = availableDates.indexOf(today);
        if (idx <= 0) return false;
        LocalDate prev = availableDates.get(idx - 1);
        return prev.getMonthValue() != today.getMonthValue() || prev.getYear() != today.getYear();
    }
}

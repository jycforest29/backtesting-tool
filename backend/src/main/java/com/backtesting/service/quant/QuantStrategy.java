package com.backtesting.service.quant;

import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 강환국 퀀트 전략의 공통 인터페이스.
 * 전략은 과거 가격만 보고 타깃 비중 시그널을 생성한다(미래 참조 금지).
 */
public interface QuantStrategy {

    QuantStrategyType type();

    String displayName();

    /** 한글 설명 (UI 표시용). */
    String description();

    /** 전략 기본 자산 유니버스 (한국 ETF). */
    List<QuantAsset> defaultUniverse();

    /**
     * 주어진 시점까지의 가격만 보고 타깃 비중 시그널을 생성.
     * 룩어헤드 방지: priceHistory는 asOfDate 이전 데이터만 포함한다고 가정.
     *
     * @param universe      사용할 자산 유니버스
     * @param priceHistory  symbol -> (date -> close) 맵 (asOfDate 이전만)
     * @param asOfDate      시그널 생성 시점
     * @param params        전략별 파라미터 (topN, momentumMonths, smaMonths 등). null 가능.
     */
    QuantSignal generateSignal(
            List<QuantAsset> universe,
            Map<String, Map<LocalDate, BigDecimal>> priceHistory,
            LocalDate asOfDate,
            StrategyParams params);

    /**
     * 이 전략이 리밸런싱해야 하는지 판단.
     * 대부분 월말(마지막 거래일)에만 true.
     * Halloween은 11월/5월 첫 거래일에만 true.
     */
    boolean shouldRebalance(LocalDate previousRebalance, LocalDate today, List<LocalDate> availableDates);

    /**
     * 유니버스가 동적/희소한지 여부.
     * true면 백테스트 엔진이
     *  - 일부 심볼의 가격 조회 실패를 허용(스킵)
     *  - 시뮬 거래일을 교집합 대신 합집합으로 계산
     * SPAC처럼 상장·합병·폐지가 잦은 유니버스에 사용.
     */
    default boolean dynamicUniverse() { return false; }

    record StrategyParams(Integer topN, Integer momentumMonths, Integer smaMonths) {
        public static StrategyParams defaults() {
            return new StrategyParams(null, null, null);
        }
        public int topN(int fallback) { return topN != null ? topN : fallback; }
        public int momentumMonths(int fallback) { return momentumMonths != null ? momentumMonths : fallback; }
        public int smaMonths(int fallback) { return smaMonths != null ? smaMonths : fallback; }
    }
}

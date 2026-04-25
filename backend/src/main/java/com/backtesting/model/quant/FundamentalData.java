package com.backtesting.model.quant;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 한 종목의 최신 재무 스냅샷. 팩터 전략의 순위 계산에 사용.
 * 실패 시 null 필드 존재 가능 → 전략은 null 체크 필수.
 */
@Data
@Builder
public class FundamentalData {
    private String stockCode;
    private String name;

    // 가격/시장 지표 (KIS에서 조회)
    private BigDecimal currentPrice;
    private BigDecimal marketCap;      // 시가총액 (원)
    private BigDecimal per;            // 주가수익비율
    private BigDecimal pbr;            // 주가순자산비율
    private BigDecimal eps;
    private BigDecimal bps;

    // 재무제표 (DART, 최신 연간 기준 — 원 단위)
    private BigDecimal revenue;            // 매출액
    private BigDecimal grossProfit;        // 매출총이익
    private BigDecimal operatingIncome;    // 영업이익
    private BigDecimal netIncome;          // 당기순이익
    private BigDecimal totalAssets;        // 자산총계
    private BigDecimal totalLiabilities;   // 부채총계
    private BigDecimal totalEquity;        // 자본총계
    private BigDecimal currentAssets;      // 유동자산
    private BigDecimal currentLiabilities; // 유동부채
    private BigDecimal operatingCashFlow;  // 영업활동현금흐름

    // 직전년도 (F-Score 계산용)
    private BigDecimal revenuePrev;
    private BigDecimal grossProfitPrev;
    private BigDecimal netIncomePrev;
    private BigDecimal totalAssetsPrev;
    private BigDecimal totalLiabilitiesPrev;
    private BigDecimal currentAssetsPrev;
    private BigDecimal currentLiabilitiesPrev;

    // 파생 지표 (derive() 호출 시 계산)
    private BigDecimal roe;                // 자기자본순이익률 (%)
    private BigDecimal roa;                // 총자산순이익률 (%)
    private BigDecimal gpa;                // GP/A = 매출총이익 / 자산총계
    private BigDecimal debtRatio;          // 부채비율 = 부채/자본 (%)
    private BigDecimal psr;                // 주가매출비율 = 시총 / 매출액
    private BigDecimal pcr;                // 주가현금흐름비율 = 시총 / 영업CF
    private Integer fScore;                // Piotroski F-Score (0~9, 간소화 버전)

    /**
     * 최신 연도 데이터와 전년도로부터 파생 지표 계산. null-safe.
     */
    public void derive() {
        this.roe = ratioPercent(netIncome, totalEquity);
        this.roa = ratioPercent(netIncome, totalAssets);
        this.gpa = ratio(grossProfit, totalAssets);
        this.debtRatio = ratioPercent(totalLiabilities, totalEquity);
        this.psr = ratio(marketCap, revenue);
        this.pcr = ratio(marketCap, operatingCashFlow);
        this.fScore = computeFScore();
    }

    /** Piotroski F-Score 간소화. 0~9. 데이터 부족 항목은 0점 처리. */
    private Integer computeFScore() {
        int s = 0;
        // 수익성 4점
        if (isPositive(netIncome)) s++;                                 // ROA > 0
        if (isPositive(operatingCashFlow)) s++;                         // OCF > 0
        if (gtZero(roa) && gtZero(ratioPercent(netIncomePrev, totalAssetsPrev))
                && ratioPercent(netIncome, totalAssets).compareTo(
                    ratioPercent(netIncomePrev, totalAssetsPrev)) > 0) s++;   // ROA 상승
        if (isPositive(operatingCashFlow) && isPositive(netIncome)
                && operatingCashFlow.compareTo(netIncome) > 0) s++;     // OCF > NI (이익 질)

        // 레버리지/유동성 3점
        BigDecimal debtPrev = ratio(totalLiabilitiesPrev, totalAssetsPrev);
        BigDecimal debtCur = ratio(totalLiabilities, totalAssets);
        if (debtPrev != null && debtCur != null && debtCur.compareTo(debtPrev) < 0) s++;   // 부채/자산 감소
        BigDecimal curPrev = ratio(currentAssetsPrev, currentLiabilitiesPrev);
        BigDecimal curNow = ratio(currentAssets, currentLiabilities);
        if (curPrev != null && curNow != null && curNow.compareTo(curPrev) > 0) s++;       // 유동비율 증가
        // 주식수 증가 여부는 DART 기본 API로 얻기 번거로움. 단순화: 항상 +1 skip (안전측).

        // 운영 효율 2점
        BigDecimal gmPrev = ratio(grossProfitPrev, revenuePrev);
        BigDecimal gmNow = ratio(grossProfit, revenue);
        if (gmPrev != null && gmNow != null && gmNow.compareTo(gmPrev) > 0) s++;           // 매출총이익률 증가
        BigDecimal turnPrev = ratio(revenuePrev, totalAssetsPrev);
        BigDecimal turnNow = ratio(revenue, totalAssets);
        if (turnPrev != null && turnNow != null && turnNow.compareTo(turnPrev) > 0) s++;   // 자산회전율 증가

        return s;
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
    private static boolean gtZero(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static BigDecimal ratio(BigDecimal num, BigDecimal denom) {
        if (num == null || denom == null || denom.signum() == 0) return null;
        return num.divide(denom, 6, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal ratioPercent(BigDecimal num, BigDecimal denom) {
        BigDecimal r = ratio(num, denom);
        return r == null ? null : r.multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

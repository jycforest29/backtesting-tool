package com.backtesting.model.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class FundamentalDataTest {

    private FundamentalData base() {
        return FundamentalData.builder()
                .stockCode("005930")
                .name("삼성전자")
                .currentPrice(new BigDecimal("70000"))
                .marketCap(new BigDecimal("400000000000000")) // 400조
                .per(new BigDecimal("10"))
                .pbr(new BigDecimal("1.2"))
                .revenue(new BigDecimal("300000000000000"))
                .grossProfit(new BigDecimal("100000000000000"))
                .operatingIncome(new BigDecimal("40000000000000"))
                .netIncome(new BigDecimal("30000000000000"))
                .totalAssets(new BigDecimal("500000000000000"))
                .totalLiabilities(new BigDecimal("150000000000000"))
                .totalEquity(new BigDecimal("350000000000000"))
                .currentAssets(new BigDecimal("200000000000000"))
                .currentLiabilities(new BigDecimal("60000000000000"))
                .operatingCashFlow(new BigDecimal("35000000000000"))
                // 전년도 비교값
                .revenuePrev(new BigDecimal("280000000000000"))
                .grossProfitPrev(new BigDecimal("90000000000000"))
                .netIncomePrev(new BigDecimal("25000000000000"))
                .totalAssetsPrev(new BigDecimal("480000000000000"))
                .totalLiabilitiesPrev(new BigDecimal("160000000000000"))
                .currentAssetsPrev(new BigDecimal("180000000000000"))
                .currentLiabilitiesPrev(new BigDecimal("65000000000000"))
                .build();
    }

    @Test
    void derive_computesAllRatios() {
        FundamentalData f = base();
        f.derive();

        // ROE = NI/Equity = 30/350 ≈ 8.57%
        assertNotNull(f.getRoe());
        assertEquals(new BigDecimal("8.57"), f.getRoe());

        // ROA = NI/Assets = 30/500 = 6%
        assertNotNull(f.getRoa());
        assertEquals(new BigDecimal("6.00"), f.getRoa());

        // GP/A = 100/500 = 0.2
        assertNotNull(f.getGpa());
        assertEquals(0, f.getGpa().compareTo(new BigDecimal("0.2")));

        // Debt ratio = 150/350 ≈ 42.86%
        assertNotNull(f.getDebtRatio());
        assertEquals(new BigDecimal("42.86"), f.getDebtRatio());

        // PSR = 400/300 ≈ 1.333
        assertNotNull(f.getPsr());
        assertEquals(0, f.getPsr().compareTo(new BigDecimal("1.333333")));
    }

    @Test
    void fScore_highWhenAllCriteriaMet() {
        FundamentalData f = base();
        f.derive();

        // 본 시나리오: ROA > 0, OCF > 0, ROA y/y 개선, OCF > NI(35>30),
        // 부채/자산 감소(150/500=0.30 < 160/480=0.333), 유동비율 증가,
        // 매출총이익률 증가, 자산회전율 증가
        assertNotNull(f.getFScore());
        assertTrue(f.getFScore() >= 6, "모든 지표 긍정 → F-Score ≥ 6. actual=" + f.getFScore());
    }

    @Test
    void fScore_lowForDeterioratingCompany() {
        FundamentalData f = FundamentalData.builder()
                .stockCode("999999")
                .currentPrice(new BigDecimal("1000"))
                .marketCap(new BigDecimal("100000000000"))
                .netIncome(new BigDecimal("-5000000000"))   // 적자
                .operatingCashFlow(new BigDecimal("-3000000000"))
                .totalAssets(new BigDecimal("100000000000"))
                .totalLiabilities(new BigDecimal("80000000000"))
                .totalEquity(new BigDecimal("20000000000"))
                .revenue(new BigDecimal("50000000000"))
                .grossProfit(new BigDecimal("10000000000"))
                .currentAssets(new BigDecimal("30000000000"))
                .currentLiabilities(new BigDecimal("50000000000"))
                // 전년
                .netIncomePrev(new BigDecimal("2000000000"))
                .totalAssetsPrev(new BigDecimal("110000000000"))
                .totalLiabilitiesPrev(new BigDecimal("70000000000"))
                .revenuePrev(new BigDecimal("55000000000"))
                .grossProfitPrev(new BigDecimal("15000000000"))
                .currentAssetsPrev(new BigDecimal("40000000000"))
                .currentLiabilitiesPrev(new BigDecimal("45000000000"))
                .build();
        f.derive();

        assertNotNull(f.getFScore());
        assertTrue(f.getFScore() <= 2, "악화 기업 F-Score ≤ 2. actual=" + f.getFScore());
    }

    @Test
    void derive_nullSafeWithMissingData() {
        FundamentalData f = FundamentalData.builder()
                .stockCode("999999")
                .currentPrice(new BigDecimal("1000"))
                .build();

        // 예외 없이 실행되어야 함
        assertDoesNotThrow(f::derive);
        // 파생 지표는 모두 null 또는 F-Score는 0~2
        assertNull(f.getRoe());
        assertNull(f.getGpa());
    }
}

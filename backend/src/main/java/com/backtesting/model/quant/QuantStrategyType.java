package com.backtesting.model.quant;

/**
 * 강환국 퀀트 전략 분류. 가격 데이터만으로 구현 가능한 계층 A 5종.
 */
public enum QuantStrategyType {
    // ===== 계층 A: 자산배분 (ETF 기반, 가격 데이터만 필요) =====
    VAA,              // Vigilant Asset Allocation (공격형 4 / 방어형 3, 모멘텀 스코어 스위칭)
    DAA,              // Defensive Asset Allocation (카나리아 자산 기반 위험 감지)
    LAA,              // Lethargic AA (정적 75% + 가변 25%, SPY 10개월선)
    DUAL_MOMENTUM,    // 절대+상대 듀얼 모멘텀 (Antonacci)
    HALLOWEEN,        // 할로윈 전략 (11~4월 주식, 5~10월 채권/현금)
    ARIRANG_FACTOR_ROTATION,  // 한화자산운용 ARIRANG ETF 3/6/12M 복합 모멘텀 로테이션
    SPAC_EVENT_DRIVEN,        // KRX SPAC 공모가 밴드 매수 + 합병공시/밴드이탈 청산

    // ===== 계층 B: 팩터 투자 (개별 종목 선정, 재무제표 필요) =====
    MAGIC_FORMULA,    // 신마법공식 — ROA 순위 + PER 순위
    SUPER_VALUE,      // 슈퍼 가치 — PER+PBR+PSR+PCR 복합 순위
    SUPER_QUALITY,    // 슈퍼 퀄리티 — GP/A + ROE + F-Score
    ULTRA,            // 울트라 — 밸류 × 퀄리티 × 모멘텀 3단 결합
    F_SCORE           // Piotroski F-Score 8~9점 + 저PBR
}

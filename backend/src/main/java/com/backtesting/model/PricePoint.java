package com.backtesting.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Redis @Cacheable 대상 (KisMarketDataService.ChartResult 의 priceHistory).
 * Jackson 역직렬화에 default ctor 필수 — 빠지면 캐시 hit 시 SerializationException.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricePoint {
    private LocalDate date;
    private BigDecimal close;
}

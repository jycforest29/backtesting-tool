package com.backtesting.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@AllArgsConstructor
public class PricePoint {
    private LocalDate date;
    private BigDecimal close;
}
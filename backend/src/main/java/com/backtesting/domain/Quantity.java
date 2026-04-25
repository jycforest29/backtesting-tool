package com.backtesting.domain;

/**
 * 주문/체결 수량. long 래퍼지만 음수 불가 / 0 허용 여부를 시그니처에서 표현.
 *
 * 한국 주식은 소수점 수량 없음 — 현재는 long 으로 충분. 해외 fractional 지원 시 BigDecimal 로.
 */
public record Quantity(long value) {

    public Quantity {
        if (value < 0) {
            throw new IllegalArgumentException("Quantity must be non-negative: " + value);
        }
    }

    public static Quantity of(long value) { return new Quantity(value); }
    public static final Quantity ZERO = new Quantity(0);

    public Quantity plus(Quantity other)  { return new Quantity(value + other.value); }
    public Quantity minus(Quantity other) { return new Quantity(value - other.value); }

    public boolean isZero()     { return value == 0; }
    public boolean isPositive() { return value > 0; }

    @Override public String toString() { return Long.toString(value); }
}

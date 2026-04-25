package com.backtesting.domain;

import com.backtesting.model.AssetType;

import java.util.Objects;

/**
 * 종목 식별자 — (market, code, exchange) 3-tuple.
 *
 * 같은 code "005930" 이라도 KR_STOCK 컨텍스트와 US_STOCK 컨텍스트에서는 다른 종목.
 * equals / hashCode 는 세 필드 모두 기반.
 */
public record Symbol(AssetType market, String code, String exchange) {

    public Symbol {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        // exchange 는 국내 주식에서는 null 이 정상.
    }

    public static Symbol domestic(String code) {
        return new Symbol(AssetType.KR_STOCK, code, null);
    }

    public static Symbol overseas(AssetType market, String code, String exchange) {
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalArgumentException("overseas symbol must have exchange");
        }
        return new Symbol(market, code, exchange);
    }

    public boolean isDomestic() { return market == AssetType.KR_STOCK; }

    @Override
    public String toString() {
        return exchange == null ? market + ":" + code : market + ":" + code + "@" + exchange;
    }
}

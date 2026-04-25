package com.backtesting.service.kis;

import com.backtesting.model.AssetType;

public record MarketSymbol(AssetType market, String code, String exchange, String currency) {

    public static MarketSymbol of(AssetType market, String code) {
        return switch (market) {
            case KR_STOCK -> new MarketSymbol(market, normalizeKr(code), "KRX", "KRW");
            case US_STOCK -> new MarketSymbol(market, code.toUpperCase(), "NAS", "USD");
            case JP_STOCK -> new MarketSymbol(market, code, "TSE", "JPY");
        };
    }

    public MarketSymbol withExchange(String newExchange) {
        return new MarketSymbol(market, code, newExchange, currency);
    }

    public boolean isDomestic() {
        return market == AssetType.KR_STOCK;
    }

    private static String normalizeKr(String code) {
        String c = code.replace(".KS", "").replace(".KQ", "").trim();
        return c.length() < 6 ? String.format("%06d", Integer.parseInt(c)) : c;
    }
}

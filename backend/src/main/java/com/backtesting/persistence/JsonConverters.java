package com.backtesting.persistence;

import com.backtesting.model.OcoPosition;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantExecutionLog;
import com.backtesting.model.quant.QuantSignal;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 필드 타입별 AttributeConverter 구현 모음.
 * JPA 필드에 @Convert(converter = ...Class) 로 연결.
 */
public final class JsonConverters {

    private JsonConverters() {}

    @Converter
    public static class TakeProfitList extends JsonConverter<List<OcoPosition.TakeProfitLegState>> {
        public TakeProfitList() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class StringBigDecimalMap extends JsonConverter<Map<String, BigDecimal>> {
        public StringBigDecimalMap() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class QuantAssetList extends JsonConverter<List<QuantAsset>> {
        public QuantAssetList() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class QuantSignalConv extends JsonConverter<QuantSignal> {
        public QuantSignalConv() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class OrderOutcomeList extends JsonConverter<List<QuantExecutionLog.OrderOutcome>> {
        public OrderOutcomeList() {
            super(new TypeReference<>() {});
        }
    }
}

package com.backtesting.service.quant;

import com.backtesting.model.quant.QuantStrategyType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 전략 5종을 타입으로 조회. 컨트롤러/서비스에서 주입받아 사용.
 */
@Component
public class QuantStrategyRegistry {

    private final Map<QuantStrategyType, QuantStrategy> strategies = new EnumMap<>(QuantStrategyType.class);

    public QuantStrategyRegistry(List<QuantStrategy> list) {
        for (QuantStrategy s : list) {
            strategies.put(s.type(), s);
        }
    }

    public QuantStrategy get(QuantStrategyType type) {
        QuantStrategy s = strategies.get(type);
        if (s == null) throw new IllegalArgumentException("Unknown strategy: " + type);
        return s;
    }

    public boolean isKnown(QuantStrategyType type) {
        return type != null && strategies.containsKey(type);
    }

    public List<QuantStrategy> all() {
        return List.copyOf(strategies.values());
    }
}

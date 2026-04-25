package com.backtesting.service.dart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 강환국 팩터 전략의 스크리닝 유니버스.
 * kospi-universe.json 에서 큐레이션된 종목 리스트를 로드.
 */
@Slf4j
@Service
public class KospiUniverseService {

    public record Stock(String code, String name) {}

    private List<Stock> stocks = List.of();

    @PostConstruct
    public void init() {
        try {
            ObjectMapper m = new ObjectMapper();
            JsonNode root = m.readTree(new ClassPathResource("kospi-universe.json").getInputStream());
            // 중복 코드 제거 (JSON에 수동 편집 중 중복이 생길 수 있음)
            Map<String, Stock> dedup = new LinkedHashMap<>();
            for (JsonNode n : root.path("stocks")) {
                String code = n.path("code").asText();
                String name = n.path("name").asText();
                if (code.isBlank()) continue;
                dedup.putIfAbsent(code, new Stock(code, name));
            }
            this.stocks = List.copyOf(dedup.values());
            log.info("KOSPI universe loaded: {} stocks", stocks.size());
        } catch (Exception e) {
            log.error("Failed to load kospi-universe.json: {}", e.getMessage(), e);
            this.stocks = List.of();
        }
    }

    public List<Stock> all() {
        return stocks;
    }

    /** 커스텀 리스트 재정의 (런타임에 유니버스 변경 시). */
    public void setUniverse(List<Stock> custom) {
        this.stocks = custom == null ? List.of() : List.copyOf(custom);
    }

    public List<String> codes() {
        List<String> out = new ArrayList<>();
        for (Stock s : stocks) out.add(s.code());
        return out;
    }
}

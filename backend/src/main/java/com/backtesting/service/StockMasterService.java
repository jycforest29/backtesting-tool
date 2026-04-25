package com.backtesting.service;

import com.backtesting.model.AssetType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 큐레이션된 종목 마스터. 이름/코드 부분일치 검색 제공.
 * 목록에 없는 심볼은 KisMarketDataService.search()에서 실제 현재가 조회로 검증.
 */
@Slf4j
@Service
public class StockMasterService {

    public record StockEntry(String code, String name, String exchange) {}

    private final Map<AssetType, List<StockEntry>> catalog = new EnumMap<>(AssetType.class);

    @PostConstruct
    void load() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new ClassPathResource("stocks-master.json").getInputStream());
            for (AssetType market : AssetType.values()) {
                List<StockEntry> list = new ArrayList<>();
                JsonNode arr = root.path(market.name());
                if (arr.isArray()) {
                    Set<String> seenCodes = new HashSet<>();
                    for (JsonNode node : arr) {
                        String code = node.path("code").asText();
                        if (code.isBlank() || !seenCodes.add(code)) continue;
                        list.add(new StockEntry(
                                code,
                                node.path("name").asText(code),
                                node.path("exchange").asText("")
                        ));
                    }
                }
                catalog.put(market, List.copyOf(list));
            }
            log.info("Stock master loaded: KR={}, US={}, JP={}",
                    catalog.get(AssetType.KR_STOCK).size(),
                    catalog.get(AssetType.US_STOCK).size(),
                    catalog.get(AssetType.JP_STOCK).size());
        } catch (Exception e) {
            log.warn("Failed to load stock master: {}", e.getMessage());
            for (AssetType t : AssetType.values()) catalog.putIfAbsent(t, List.of());
        }
    }

    /** 코드/이름 부분일치. 코드 완전일치 우선, 그 다음 이름/코드 부분일치. 최대 10건. */
    public List<StockEntry> search(AssetType market, String query) {
        if (market == null || query == null || query.isBlank()) return List.of();
        String q = query.trim().toLowerCase();
        List<StockEntry> source = catalog.getOrDefault(market, List.of());

        List<StockEntry> exact = new ArrayList<>();
        List<StockEntry> prefix = new ArrayList<>();
        List<StockEntry> contains = new ArrayList<>();
        for (StockEntry e : source) {
            String code = e.code().toLowerCase();
            String name = e.name().toLowerCase();
            if (code.equals(q)) exact.add(e);
            else if (code.startsWith(q) || name.startsWith(q)) prefix.add(e);
            else if (code.contains(q) || name.contains(q)) contains.add(e);
        }
        List<StockEntry> result = new ArrayList<>();
        result.addAll(exact);
        result.addAll(prefix);
        result.addAll(contains);
        return result.size() > 10 ? result.subList(0, 10) : result;
    }

    /** 특정 시장의 마스터 전체 반환. 스캐너 등에서 후보 풀로 사용. */
    public List<StockEntry> getAll(AssetType market) {
        return catalog.getOrDefault(market, List.of());
    }

    /** 코드로 이름 조회 (있으면). */
    public Optional<StockEntry> lookup(AssetType market, String code) {
        if (market == null || code == null) return Optional.empty();
        String c = code.trim();
        return catalog.getOrDefault(market, List.of()).stream()
                .filter(e -> e.code().equals(c))
                .findFirst();
    }
}

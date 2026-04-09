package com.backtesting.controller;

import com.backtesting.model.BacktestRequest;
import com.backtesting.model.BacktestResult;
import com.backtesting.service.BacktestService;
import com.backtesting.service.YahooFinanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;
    private final YahooFinanceService yahooFinanceService;

    @PostMapping("/backtest")
    public ResponseEntity<?> backtest(@RequestBody BacktestRequest request) {
        try {
            BacktestResult result = backtestService.calculate(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q,
                                    @RequestParam(required = false) String market) {
        try {
            List<Map<String, String>> results = yahooFinanceService.search(q);
            if (market != null) {
                results = results.stream().filter(r -> {
                    String exchange = r.getOrDefault("exchange", "");
                    return switch (market) {
                        case "US" -> exchange.matches("NYQ|NMS|NGM|PCX|ASE|BTS");
                        case "KR" -> exchange.matches("KSC|KOE");
                        case "JP" -> exchange.matches("JPX|TYO");
                        default -> true;
                    };
                }).toList();
            }
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/presets")
    public Map<String, Object> getPresets() {
        return Map.of(
                "forex", List.of(
                        Map.of("symbol", "KRW=X", "name", "USD/KRW"),
                        Map.of("symbol", "JPY=X", "name", "USD/JPY"),
                        Map.of("symbol", "KRWJPY=X", "name", "KRW/JPY"),
                        Map.of("symbol", "EURUSD=X", "name", "EUR/USD")
                ),
                "commodities", Map.of(
                        "gold", Map.of("symbol", "GC=F", "name", "Gold Futures"),
                        "silver", Map.of("symbol", "SI=F", "name", "Silver Futures")
                )
        );
    }
}

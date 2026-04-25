package com.backtesting.service;

import com.backtesting.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
public class StressTestService {

    /** Factor → AssetType → 민감도(β) */
    private static final Map<String, Map<String, Double>> FACTOR_BETAS = Map.of(
            "KOSPI", Map.of(
                    "KR_STOCK", 1.0, "US_STOCK", 0.3, "JP_STOCK", 0.4
            ),
            "NASDAQ", Map.of(
                    "US_STOCK", 0.95, "KR_STOCK", 0.45, "JP_STOCK", 0.35
            ),
            "SP500", Map.of(
                    "US_STOCK", 1.0, "KR_STOCK", 0.5, "JP_STOCK", 0.4
            ),
            "NIKKEI", Map.of(
                    "JP_STOCK", 1.0, "KR_STOCK", 0.35, "US_STOCK", 0.3
            ),
            "USD_KRW", Map.of(
                    "KR_STOCK", -0.6, "US_STOCK", 0.05, "JP_STOCK", 0.1
            ),
            "INTEREST_RATE", Map.of(
                    "US_STOCK", -0.5, "KR_STOCK", -0.6, "JP_STOCK", -0.4
            )
    );

    private static final Map<String, PresetScenario> PRESETS = Map.of(
            "COVID_2020", new PresetScenario(
                    "COVID-19 폭락 (2020년 3월)",
                    "팬데믹 발발로 S&P500 -34%, KOSPI -30%",
                    List.of(
                            shock("SP500", "-34"),
                            shock("KOSPI", "-30"),
                            shock("NIKKEI", "-27")
                    )
            ),
            "GFC_2008", new PresetScenario(
                    "글로벌 금융위기 (2008년)",
                    "리먼 파산. S&P500 -50%, KOSPI -40%, USD/KRW +40%",
                    List.of(
                            shock("SP500", "-50"),
                            shock("KOSPI", "-40"),
                            shock("NIKKEI", "-42"),
                            shock("USD_KRW", "40")
                    )
            ),
            "RATE_HIKE", new PresetScenario(
                    "공격적 금리 인상 (+3%p)",
                    "중앙은행 300bp 인상. 주식 -15~22%",
                    List.of(
                            shock("INTEREST_RATE", "300"),
                            shock("SP500", "-18"),
                            shock("KOSPI", "-22"),
                            shock("NIKKEI", "-15")
                    )
            ),
            "KR_CRISIS", new PresetScenario(
                    "한국 지정학 위기",
                    "KOSPI -25%, USD/KRW +20%",
                    List.of(
                            shock("KOSPI", "-25"),
                            shock("USD_KRW", "20")
                    )
            )
    );

    private static StressTestRequest.ShockScenario shock(String factor, String pct) {
        StressTestRequest.ShockScenario s = new StressTestRequest.ShockScenario();
        s.setFactor(factor);
        s.setShockPercent(new BigDecimal(pct));
        return s;
    }

    private record PresetScenario(String name, String description, List<StressTestRequest.ShockScenario> shocks) {}

    public StressTestResult calculate(StressTestRequest request) {
        List<StressTestRequest.ShockScenario> shocks;
        String scenarioName, scenarioDesc;

        if (request.getPresetScenario() != null && PRESETS.containsKey(request.getPresetScenario())) {
            PresetScenario preset = PRESETS.get(request.getPresetScenario());
            shocks = preset.shocks();
            scenarioName = preset.name();
            scenarioDesc = preset.description();
        } else if (request.getShocks() != null && !request.getShocks().isEmpty()) {
            shocks = request.getShocks();
            scenarioName = "커스텀 시나리오";
            scenarioDesc = "사용자 정의 시나리오";
        } else {
            throw new IllegalArgumentException("Either presetScenario or shocks must be provided");
        }

        BigDecimal totalBefore = request.getPortfolioValue();
        if (totalBefore == null || totalBefore.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Portfolio value must be positive");
        }

        List<StressTestResult.AssetImpact> impacts = new ArrayList<>();
        BigDecimal totalAfter = BigDecimal.ZERO;

        for (PortfolioAssetRequest asset : request.getAssets()) {
            BigDecimal weight = asset.getWeight().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal before = totalBefore.multiply(weight);

            double totalShockPct = 0;
            double primaryBeta = 0;
            for (StressTestRequest.ShockScenario shock : shocks) {
                Map<String, Double> betas = FACTOR_BETAS.getOrDefault(shock.getFactor(), Map.of());
                double beta = betas.getOrDefault(asset.getAssetType().name(), 0.0);
                totalShockPct += beta * shock.getShockPercent().doubleValue();
                if (Math.abs(beta) > Math.abs(primaryBeta)) primaryBeta = beta;
            }
            totalShockPct = Math.max(totalShockPct, -95);

            BigDecimal changePct = BigDecimal.valueOf(totalShockPct);
            BigDecimal after = before.multiply(
                    BigDecimal.ONE.add(changePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
            ).setScale(2, RoundingMode.HALF_UP);

            impacts.add(StressTestResult.AssetImpact.builder()
                    .symbol(asset.getSymbol())
                    .name(asset.getName())
                    .weight(asset.getWeight())
                    .valueBefore(before.setScale(2, RoundingMode.HALF_UP))
                    .valueAfter(after)
                    .changePercent(changePct.setScale(2, RoundingMode.HALF_UP))
                    .sensitivity(BigDecimal.valueOf(primaryBeta).setScale(2, RoundingMode.HALF_UP))
                    .build());
            totalAfter = totalAfter.add(after);
        }

        BigDecimal portfolioChange = totalAfter.subtract(totalBefore);
        BigDecimal portfolioChangePct = portfolioChange
                .divide(totalBefore, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        List<StressTestResult.ShockApplied> shocksApplied = shocks.stream()
                .map(s -> StressTestResult.ShockApplied.builder()
                        .factor(s.getFactor())
                        .shockPercent(s.getShockPercent())
                        .build())
                .toList();

        return StressTestResult.builder()
                .scenarioName(scenarioName)
                .scenarioDescription(scenarioDesc)
                .shocksApplied(shocksApplied)
                .portfolioValueBefore(totalBefore.setScale(2, RoundingMode.HALF_UP))
                .portfolioValueAfter(totalAfter.setScale(2, RoundingMode.HALF_UP))
                .portfolioChange(portfolioChange.setScale(2, RoundingMode.HALF_UP))
                .portfolioChangePercent(portfolioChangePct)
                .assetImpacts(impacts)
                .riskLevel(determineRiskLevel(portfolioChangePct.doubleValue()))
                .build();
    }

    public Map<String, String> getPresetScenarios() {
        Map<String, String> result = new LinkedHashMap<>();
        PRESETS.forEach((k, v) -> result.put(k, v.name()));
        return result;
    }

    private String determineRiskLevel(double changePct) {
        if (changePct <= -30) return "CRITICAL";
        if (changePct <= -15) return "HIGH";
        if (changePct <= -5) return "MEDIUM";
        return "LOW";
    }
}

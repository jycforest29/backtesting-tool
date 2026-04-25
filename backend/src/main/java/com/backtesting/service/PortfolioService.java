package com.backtesting.service;

import com.backtesting.model.*;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.MarketSymbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final KisMarketDataService marketData;

    private static final BigDecimal RISK_FREE_RATE = new BigDecimal("0.04");
    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final MathContext MC = MathContext.DECIMAL64;

    // 벤치마크 심볼 → 시장 매핑. 확장은 엔트리 추가만.
    private static final Map<String, AssetType> BENCHMARK_MARKETS = Map.of(
            "SPY", AssetType.US_STOCK,
            "QQQ", AssetType.US_STOCK,
            "AGG", AssetType.US_STOCK,
            "069500", AssetType.KR_STOCK,
            "229200", AssetType.KR_STOCK
    );

    private static final Map<String, String> BENCHMARK_NAMES = Map.of(
            "SPY", "S&P 500 (SPY)",
            "QQQ", "Nasdaq 100 (QQQ)",
            "AGG", "US Bonds (AGG)",
            "069500", "KODEX 200",
            "229200", "KODEX 코스닥150",
            "DEPOSIT", "예금 (3.5%)"
    );

    public PortfolioBacktestResult calculate(PortfolioBacktestRequest request) {
        validateRequest(request);

        Map<String, List<PricePoint>> assetPrices = new LinkedHashMap<>();
        Map<String, String> assetNames = new LinkedHashMap<>();
        Map<String, MarketSymbol> resolved = new LinkedHashMap<>();

        for (PortfolioAssetRequest asset : request.getAssets()) {
            MarketSymbol sym = MarketSymbol.of(asset.getAssetType(), asset.getSymbol());
            resolved.put(asset.getSymbol(), sym);
            try {
                KisMarketDataService.ChartResult chart =
                        marketData.getChartData(sym, request.getStartDate());
                assetPrices.put(sym.code(), chart.priceHistory());
                assetNames.put(sym.code(), asset.getName() != null ? asset.getName() : chart.name());
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch data for " + sym.code() + ": " + e.getMessage(), e);
            }
        }

        List<LocalDate> commonDates = findCommonDates(assetPrices);
        if (commonDates.size() < 2) {
            throw new IllegalArgumentException("Not enough overlapping price data across assets");
        }

        Map<String, Map<LocalDate, BigDecimal>> priceMaps = new LinkedHashMap<>();
        for (var entry : assetPrices.entrySet()) {
            Map<LocalDate, BigDecimal> dateMap = new LinkedHashMap<>();
            for (PricePoint pp : entry.getValue()) {
                dateMap.put(pp.getDate(), pp.getClose());
            }
            priceMaps.put(entry.getKey(), dateMap);
        }

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (PortfolioAssetRequest asset : request.getAssets()) {
            String code = resolved.get(asset.getSymbol()).code();
            BigDecimal w = asset.getWeight().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            weights.put(code, w);
        }

        SimulationResult sim = request.isDcaEnabled() && request.getDcaMonthlyAmount() != null
                ? simulateDCA(commonDates, priceMaps, weights, request)
                : simulateLumpSum(commonDates, priceMaps, weights, request);

        RiskMetrics riskMetrics = calculateRiskMetrics(sim.portfolioValues, sim.totalInvested, commonDates);

        List<PortfolioBacktestResult.AssetPerformance> assetPerformances = new ArrayList<>();
        for (PortfolioAssetRequest asset : request.getAssets()) {
            MarketSymbol sym = resolved.get(asset.getSymbol());
            Map<LocalDate, BigDecimal> priceMap = priceMaps.get(sym.code());
            BigDecimal firstPrice = priceMap.get(commonDates.get(0));
            BigDecimal lastPrice = priceMap.get(commonDates.get(commonDates.size() - 1));

            BigDecimal assetReturn = BigDecimal.ZERO;
            if (firstPrice != null && lastPrice != null && firstPrice.compareTo(BigDecimal.ZERO) != 0) {
                assetReturn = lastPrice.subtract(firstPrice)
                        .divide(firstPrice, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal allocated = request.getInvestmentAmount()
                    .multiply(asset.getWeight())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            assetPerformances.add(PortfolioBacktestResult.AssetPerformance.builder()
                    .symbol(sym.code())
                    .name(assetNames.get(sym.code()))
                    .assetType(asset.getAssetType())
                    .weight(asset.getWeight())
                    .returnPercent(assetReturn)
                    .allocated(allocated)
                    .currentValue(allocated.multiply(BigDecimal.ONE.add(assetReturn.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                            .setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        List<PortfolioBacktestResult.PortfolioValuePoint> valueHistory =
                buildValueHistory(commonDates, sim.portfolioValues, sim.investedAmounts);

        BigDecimal finalValue = sim.portfolioValues.get(sim.portfolioValues.size() - 1);
        BigDecimal profitLoss = finalValue.subtract(sim.totalInvested);
        BigDecimal totalReturnPct = profitLoss
                .divide(sim.totalInvested, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        List<BenchmarkResult> benchmarks = null;
        if (request.isBenchmarkEnabled() && request.getBenchmarkSymbols() != null) {
            benchmarks = calculateBenchmarks(request, commonDates, sim.totalInvested);
        }

        TaxFeeResult taxFeeResult = null;
        if (request.getTaxFeeConfig() != null && request.getTaxFeeConfig().isEnabled()) {
            taxFeeResult = calculateTaxFee(request.getTaxFeeConfig(), profitLoss, sim.totalInvested,
                    sim.rebalanceCount + (request.isDcaEnabled() ? sim.dcaContributions * 2 : 2));
        }

        return PortfolioBacktestResult.builder()
                .startDate(request.getStartDate())
                .investmentCurrency(request.getInvestmentCurrency() != null ? request.getInvestmentCurrency() : "KRW")
                .totalInvested(sim.totalInvested.setScale(2, RoundingMode.HALF_UP))
                .finalValue(finalValue.setScale(2, RoundingMode.HALF_UP))
                .profitLoss(profitLoss.setScale(2, RoundingMode.HALF_UP))
                .totalReturnPercent(totalReturnPct)
                .riskMetrics(riskMetrics)
                .assetPerformances(assetPerformances)
                .valueHistory(valueHistory)
                .dcaEnabled(request.isDcaEnabled())
                .dcaMonthlyAmount(request.getDcaMonthlyAmount())
                .dcaContributions(sim.dcaContributions)
                .rebalancePeriod(request.getRebalancePeriod() != null ? request.getRebalancePeriod() : RebalancePeriod.NONE)
                .rebalanceCount(sim.rebalanceCount)
                .benchmarks(benchmarks)
                .taxFeeResult(taxFeeResult)
                .build();
    }

    // ========== SIMULATION ==========

    private static class SimulationResult {
        List<BigDecimal> portfolioValues;
        List<BigDecimal> investedAmounts;
        BigDecimal totalInvested;
        int rebalanceCount;
        int dcaContributions;
    }

    private SimulationResult simulateLumpSum(List<LocalDate> dates,
                                             Map<String, Map<LocalDate, BigDecimal>> priceMaps,
                                             Map<String, BigDecimal> weights,
                                             PortfolioBacktestRequest request) {
        SimulationResult r = new SimulationResult();
        r.portfolioValues = new ArrayList<>();
        r.investedAmounts = new ArrayList<>();
        r.totalInvested = request.getInvestmentAmount();
        r.rebalanceCount = 0;

        BigDecimal initial = request.getInvestmentAmount();
        LocalDate firstDate = dates.get(0);

        Map<String, BigDecimal> units = new LinkedHashMap<>();
        for (var e : weights.entrySet()) {
            BigDecimal allocated = initial.multiply(e.getValue());
            BigDecimal price = priceMaps.get(e.getKey()).get(firstDate);
            units.put(e.getKey(), allocated.divide(price, 10, RoundingMode.HALF_UP));
        }

        RebalancePeriod period = request.getRebalancePeriod() != null
                ? request.getRebalancePeriod() : RebalancePeriod.NONE;
        LocalDate nextRebalance = calculateNextRebalanceDate(firstDate, period);

        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            BigDecimal value = BigDecimal.ZERO;
            for (var e : units.entrySet()) {
                value = value.add(e.getValue().multiply(priceMaps.get(e.getKey()).get(date)));
            }
            r.portfolioValues.add(value);
            r.investedAmounts.add(initial);

            if (period != RebalancePeriod.NONE && !date.isBefore(nextRebalance) && i < dates.size() - 1) {
                units = rebalance(value, weights, priceMaps, date);
                nextRebalance = calculateNextRebalanceDate(date, period);
                r.rebalanceCount++;
            }
        }
        return r;
    }

    private SimulationResult simulateDCA(List<LocalDate> dates,
                                         Map<String, Map<LocalDate, BigDecimal>> priceMaps,
                                         Map<String, BigDecimal> weights,
                                         PortfolioBacktestRequest request) {
        SimulationResult r = new SimulationResult();
        r.portfolioValues = new ArrayList<>();
        r.investedAmounts = new ArrayList<>();
        r.dcaContributions = 0;

        BigDecimal monthly = request.getDcaMonthlyAmount();
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal initial = request.getInvestmentAmount();
        LocalDate firstDate = dates.get(0);

        Map<String, BigDecimal> units = new LinkedHashMap<>();
        if (initial.compareTo(BigDecimal.ZERO) > 0) {
            for (var e : weights.entrySet()) {
                BigDecimal allocated = initial.multiply(e.getValue());
                BigDecimal price = priceMaps.get(e.getKey()).get(firstDate);
                units.put(e.getKey(), allocated.divide(price, 10, RoundingMode.HALF_UP));
            }
            totalInvested = initial;
        } else {
            for (String code : weights.keySet()) units.put(code, BigDecimal.ZERO);
        }

        RebalancePeriod period = request.getRebalancePeriod() != null
                ? request.getRebalancePeriod() : RebalancePeriod.NONE;
        LocalDate nextRebalance = calculateNextRebalanceDate(firstDate, period);

        int lastMonth = -1, lastYear = -1;
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            int m = date.getMonthValue(), y = date.getYear();
            if (i > 0 && (m != lastMonth || y != lastYear)) {
                for (var e : weights.entrySet()) {
                    BigDecimal allocated = monthly.multiply(e.getValue());
                    BigDecimal price = priceMaps.get(e.getKey()).get(date);
                    units.merge(e.getKey(), allocated.divide(price, 10, RoundingMode.HALF_UP), BigDecimal::add);
                }
                totalInvested = totalInvested.add(monthly);
                r.dcaContributions++;
            }
            lastMonth = m; lastYear = y;

            BigDecimal value = BigDecimal.ZERO;
            for (var e : units.entrySet()) {
                value = value.add(e.getValue().multiply(priceMaps.get(e.getKey()).get(date)));
            }
            r.portfolioValues.add(value);
            r.investedAmounts.add(totalInvested);

            if (period != RebalancePeriod.NONE && !date.isBefore(nextRebalance) && i < dates.size() - 1) {
                units = rebalance(value, weights, priceMaps, date);
                nextRebalance = calculateNextRebalanceDate(date, period);
                r.rebalanceCount++;
            }
        }
        r.totalInvested = totalInvested;
        return r;
    }

    private Map<String, BigDecimal> rebalance(BigDecimal total, Map<String, BigDecimal> weights,
                                              Map<String, Map<LocalDate, BigDecimal>> priceMaps, LocalDate date) {
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        for (var e : weights.entrySet()) {
            BigDecimal target = total.multiply(e.getValue());
            BigDecimal price = priceMaps.get(e.getKey()).get(date);
            units.put(e.getKey(), target.divide(price, 10, RoundingMode.HALF_UP));
        }
        return units;
    }

    private LocalDate calculateNextRebalanceDate(LocalDate from, RebalancePeriod period) {
        return switch (period) {
            case MONTHLY -> from.plusMonths(1).withDayOfMonth(1);
            case QUARTERLY -> from.plusMonths(3).withDayOfMonth(1);
            case YEARLY -> from.plusYears(1).withDayOfMonth(1).withMonth(1);
            case NONE -> LocalDate.MAX;
        };
    }

    // ========== RISK METRICS ==========

    private RiskMetrics calculateRiskMetrics(List<BigDecimal> values, BigDecimal totalInvested, List<LocalDate> dates) {
        if (values.size() < 2) return RiskMetrics.builder().build();

        List<Double> dailyReturns = new ArrayList<>();
        for (int i = 1; i < values.size(); i++) {
            double prev = values.get(i - 1).doubleValue();
            double curr = values.get(i).doubleValue();
            if (prev > 0) dailyReturns.add((curr - prev) / prev);
        }
        if (dailyReturns.isEmpty()) return RiskMetrics.builder().build();

        double meanDaily = dailyReturns.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = dailyReturns.stream().mapToDouble(r -> Math.pow(r - meanDaily, 2)).average().orElse(0);
        double dailyStd = Math.sqrt(variance);
        double downsideVar = dailyReturns.stream().filter(r -> r < 0).mapToDouble(r -> r * r).average().orElse(0);
        double downsideStd = Math.sqrt(downsideVar);

        double annualReturn = meanDaily * TRADING_DAYS_PER_YEAR * 100;
        double annualVol = dailyStd * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100;
        double riskFree = RISK_FREE_RATE.doubleValue() * 100;
        double sharpe = annualVol > 0 ? (annualReturn - riskFree) / annualVol : 0;
        double annualDownside = downsideStd * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100;
        double sortino = annualDownside > 0 ? (annualReturn - riskFree) / annualDownside : 0;

        double peak = values.get(0).doubleValue(), maxDD = 0;
        int mddStart = 0, mddEnd = 0, peakIdx = 0;
        for (int i = 1; i < values.size(); i++) {
            double v = values.get(i).doubleValue();
            if (v > peak) { peak = v; peakIdx = i; }
            double dd = (v - peak) / peak;
            if (dd < maxDD) { maxDD = dd; mddStart = peakIdx; mddEnd = i; }
        }

        double lastVal = values.get(values.size() - 1).doubleValue();
        double totalReturn = totalInvested.doubleValue() > 0
                ? ((lastVal - totalInvested.doubleValue()) / totalInvested.doubleValue()) * 100 : 0;
        long days = ChronoUnit.DAYS.between(dates.get(0), dates.get(dates.size() - 1));
        double years = days / 365.25;
        double cagr = years > 0 && totalInvested.doubleValue() > 0
                ? (Math.pow(lastVal / totalInvested.doubleValue(), 1.0 / years) - 1) * 100 : 0;

        return RiskMetrics.builder()
                .sharpeRatio(BigDecimal.valueOf(sharpe).setScale(2, RoundingMode.HALF_UP))
                .sortinoRatio(BigDecimal.valueOf(sortino).setScale(2, RoundingMode.HALF_UP))
                .maxDrawdown(BigDecimal.valueOf(maxDD * 100).setScale(2, RoundingMode.HALF_UP))
                .maxDrawdownStart(mddStart < dates.size() ? dates.get(mddStart) : null)
                .maxDrawdownEnd(mddEnd < dates.size() ? dates.get(mddEnd) : null)
                .annualVolatility(BigDecimal.valueOf(annualVol).setScale(2, RoundingMode.HALF_UP))
                .annualReturn(BigDecimal.valueOf(annualReturn).setScale(2, RoundingMode.HALF_UP))
                .totalReturn(BigDecimal.valueOf(totalReturn).setScale(2, RoundingMode.HALF_UP))
                .cagr(BigDecimal.valueOf(cagr).setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    // ========== HELPERS ==========

    private List<LocalDate> findCommonDates(Map<String, List<PricePoint>> assetPrices) {
        Set<LocalDate> common = null;
        for (var entry : assetPrices.entrySet()) {
            Set<LocalDate> dates = entry.getValue().stream()
                    .map(PricePoint::getDate)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (common == null) common = dates;
            else common.retainAll(dates);
        }
        if (common == null) return Collections.emptyList();
        return common.stream().sorted().collect(Collectors.toList());
    }

    private List<PortfolioBacktestResult.PortfolioValuePoint> buildValueHistory(
            List<LocalDate> dates, List<BigDecimal> values, List<BigDecimal> invested) {
        List<PortfolioBacktestResult.PortfolioValuePoint> hist = new ArrayList<>();
        int total = dates.size();
        int step = Math.max(1, total / 500);
        for (int i = 0; i < total; i += step) {
            hist.add(PortfolioBacktestResult.PortfolioValuePoint.builder()
                    .date(dates.get(i))
                    .value(values.get(i).setScale(2, RoundingMode.HALF_UP))
                    .invested(invested.get(i).setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        int last = total - 1;
        if (last % step != 0) {
            hist.add(PortfolioBacktestResult.PortfolioValuePoint.builder()
                    .date(dates.get(last))
                    .value(values.get(last).setScale(2, RoundingMode.HALF_UP))
                    .invested(invested.get(last).setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        return hist;
    }

    // ========== BENCHMARKS ==========

    private List<BenchmarkResult> calculateBenchmarks(PortfolioBacktestRequest request,
                                                      List<LocalDate> portfolioDates,
                                                      BigDecimal totalInvested) {
        List<BenchmarkResult> results = new ArrayList<>();
        LocalDate start = portfolioDates.get(0), end = portfolioDates.get(portfolioDates.size() - 1);
        long days = ChronoUnit.DAYS.between(start, end);
        double years = days / 365.25;

        for (String symbol : request.getBenchmarkSymbols()) {
            try {
                if ("DEPOSIT".equals(symbol)) {
                    results.add(depositBenchmark(totalInvested, portfolioDates, years));
                    continue;
                }
                AssetType market = BENCHMARK_MARKETS.getOrDefault(symbol, AssetType.US_STOCK);
                MarketSymbol sym = MarketSymbol.of(market, symbol);
                KisMarketDataService.ChartResult chart = marketData.getChartData(sym, start);
                if (chart.priceHistory().isEmpty()) continue;

                Map<LocalDate, BigDecimal> priceMap = new LinkedHashMap<>();
                for (PricePoint p : chart.priceHistory()) priceMap.put(p.getDate(), p.getClose());

                BigDecimal firstPrice = null;
                for (LocalDate d : portfolioDates) {
                    if (priceMap.containsKey(d)) { firstPrice = priceMap.get(d); break; }
                }
                if (firstPrice == null) continue;
                BigDecimal units = totalInvested.divide(firstPrice, 10, RoundingMode.HALF_UP);

                List<PortfolioBacktestResult.PortfolioValuePoint> hist = new ArrayList<>();
                BigDecimal lastKnown = firstPrice;
                int step = Math.max(1, portfolioDates.size() / 200);
                for (int i = 0; i < portfolioDates.size(); i += step) {
                    LocalDate d = portfolioDates.get(i);
                    BigDecimal price = priceMap.getOrDefault(d, lastKnown);
                    lastKnown = price;
                    hist.add(PortfolioBacktestResult.PortfolioValuePoint.builder()
                            .date(d)
                            .value(units.multiply(price).setScale(2, RoundingMode.HALF_UP))
                            .invested(totalInvested.setScale(2, RoundingMode.HALF_UP))
                            .build());
                }
                BigDecimal finalPrice = priceMap.getOrDefault(end, lastKnown);
                BigDecimal finalVal = units.multiply(finalPrice);
                double benchReturn = finalVal.subtract(totalInvested)
                        .divide(totalInvested, 6, RoundingMode.HALF_UP).doubleValue() * 100;
                double benchCagr = years > 0
                        ? (Math.pow(finalVal.doubleValue() / totalInvested.doubleValue(), 1.0 / years) - 1) * 100 : 0;

                results.add(BenchmarkResult.builder()
                        .name(BENCHMARK_NAMES.getOrDefault(symbol, chart.name()))
                        .symbol(symbol)
                        .totalReturn(BigDecimal.valueOf(benchReturn).setScale(2, RoundingMode.HALF_UP))
                        .cagr(BigDecimal.valueOf(benchCagr).setScale(2, RoundingMode.HALF_UP))
                        .finalValue(finalVal.setScale(2, RoundingMode.HALF_UP))
                        .valueHistory(hist)
                        .build());
            } catch (Exception e) {
                log.warn("Benchmark {} failed: {}", symbol, e.getMessage());
            }
        }
        return results;
    }

    private BenchmarkResult depositBenchmark(BigDecimal totalInvested, List<LocalDate> dates, double years) {
        double rate = 0.035;
        double finalVal = totalInvested.doubleValue() * Math.pow(1 + rate, years);
        double totalReturn = ((finalVal / totalInvested.doubleValue()) - 1) * 100;
        List<PortfolioBacktestResult.PortfolioValuePoint> hist = new ArrayList<>();
        LocalDate startDate = dates.get(0);
        int step = Math.max(1, dates.size() / 200);
        for (int i = 0; i < dates.size(); i += step) {
            LocalDate d = dates.get(i);
            double yrs = ChronoUnit.DAYS.between(startDate, d) / 365.25;
            double val = totalInvested.doubleValue() * Math.pow(1 + rate, yrs);
            hist.add(PortfolioBacktestResult.PortfolioValuePoint.builder()
                    .date(d)
                    .value(BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP))
                    .invested(totalInvested.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        return BenchmarkResult.builder()
                .name(BENCHMARK_NAMES.get("DEPOSIT"))
                .symbol("DEPOSIT")
                .totalReturn(BigDecimal.valueOf(totalReturn).setScale(2, RoundingMode.HALF_UP))
                .cagr(BigDecimal.valueOf(rate * 100).setScale(2, RoundingMode.HALF_UP))
                .finalValue(BigDecimal.valueOf(finalVal).setScale(2, RoundingMode.HALF_UP))
                .valueHistory(hist)
                .build();
    }

    // ========== TAX & FEE ==========

    private TaxFeeResult calculateTaxFee(TaxFeeConfig config, BigDecimal grossProfit,
                                          BigDecimal totalInvested, int tradeCount) {
        BigDecimal tradingFees = BigDecimal.ZERO, fxFees = BigDecimal.ZERO, capitalGainsTax = BigDecimal.ZERO;

        if (config.getTradingFeeRate() != null && config.getTradingFeeRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal volume = totalInvested.multiply(BigDecimal.valueOf(Math.max(tradeCount, 2)));
            tradingFees = volume.multiply(config.getTradingFeeRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        if (config.getFxFeeRate() != null && config.getFxFeeRate().compareTo(BigDecimal.ZERO) > 0) {
            fxFees = totalInvested.multiply(config.getFxFeeRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(2));
        }
        if (grossProfit.compareTo(BigDecimal.ZERO) > 0
                && config.getCapitalGainsTaxRate() != null
                && config.getCapitalGainsTaxRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal taxable = grossProfit;
            if (config.getTaxExemption() != null && config.getTaxExemption().compareTo(BigDecimal.ZERO) > 0) {
                taxable = grossProfit.subtract(config.getTaxExemption());
                if (taxable.compareTo(BigDecimal.ZERO) < 0) taxable = BigDecimal.ZERO;
            }
            capitalGainsTax = taxable.multiply(config.getCapitalGainsTaxRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        BigDecimal totalDeductions = tradingFees.add(fxFees).add(capitalGainsTax);
        BigDecimal netProfit = grossProfit.subtract(totalDeductions);
        BigDecimal netReturnPct = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.divide(totalInvested, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal effectiveTaxRate = grossProfit.compareTo(BigDecimal.ZERO) > 0
                ? totalDeductions.divide(grossProfit, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return TaxFeeResult.builder()
                .enabled(true)
                .grossProfit(grossProfit.setScale(2, RoundingMode.HALF_UP))
                .capitalGainsTax(capitalGainsTax.setScale(2, RoundingMode.HALF_UP))
                .tradingFees(tradingFees.setScale(2, RoundingMode.HALF_UP))
                .fxFees(fxFees.setScale(2, RoundingMode.HALF_UP))
                .totalDeductions(totalDeductions.setScale(2, RoundingMode.HALF_UP))
                .netProfit(netProfit.setScale(2, RoundingMode.HALF_UP))
                .netReturnPercent(netReturnPct)
                .effectiveTaxRate(effectiveTaxRate)
                .build();
    }

    private void validateRequest(PortfolioBacktestRequest request) {
        if (request.getAssets() == null || request.getAssets().isEmpty())
            throw new IllegalArgumentException("At least one asset is required");
        if (request.getStartDate() == null || request.getStartDate().isAfter(LocalDate.now()))
            throw new IllegalArgumentException("Invalid start date");
        if (request.getInvestmentAmount() == null || request.getInvestmentAmount().compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Investment amount must be non-negative");

        BigDecimal total = request.getAssets().stream()
                .map(PortfolioAssetRequest::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.subtract(BigDecimal.valueOf(100)).abs().compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("Asset weights must sum to 100% (current: " + total + "%)");

        if (request.isDcaEnabled()) {
            if (request.getDcaMonthlyAmount() == null || request.getDcaMonthlyAmount().compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("DCA monthly amount must be positive");
        }
    }
}

package com.backtesting.service.quant;

import com.backtesting.model.*;
import com.backtesting.model.quant.*;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.MarketSymbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 강환국 퀀트 전략 백테스트 엔진.
 *
 * 일별 시뮬레이션:
 *   1. 각 거래일마다 포트폴리오 가치 계산 (units * price)
 *   2. shouldRebalance(today)가 true면 당일 종가로 시그널 생성 후 리밸런싱
 *   3. 마지막 날짜까지 반복
 *
 * 룩어헤드 방지: 시그널 생성 시 priceHistory는 asOfDate 이전 데이터만 포함.
 * (실제로는 asOfDate 종가까지 사용 — 월말 리밸런싱이라 당일 종가 허용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuantBacktestService {

    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final BigDecimal RISK_FREE_RATE = new BigDecimal("0.04");

    private final KisMarketDataService marketData;
    private final QuantStrategyRegistry registry;

    public QuantBacktestResult run(QuantBacktestRequest request) {
        validate(request);
        QuantStrategy strategy = registry.get(request.getStrategyType());

        List<QuantAsset> universe = request.getCustomUniverse() != null && !request.getCustomUniverse().isEmpty()
                ? request.getCustomUniverse()
                : strategy.defaultUniverse();

        // 팩터 전략 특화 처리: 유니버스가 크고 generateSignal이 펀더멘털 기반이므로
        // 현재 펀더멘털로 상위 N종목을 먼저 선정한 뒤 그 종목만 로드하여 백테스트 수행.
        // 주의: 이 접근은 생존편향 내재. UI에서 경고 표시.
        if (strategy instanceof FactorStrategy fs) {
            int topN = request.getTopN() != null ? request.getTopN() : fs.defaultTopN();
            QuantSignal preview = strategy.generateSignal(universe, Map.of(), LocalDate.now(),
                    new QuantStrategy.StrategyParams(topN, null, null));
            Set<String> selected = preview.getTargetWeights().keySet();
            universe = universe.stream()
                    .filter(a -> selected.contains(a.getSymbol()))
                    .toList();
            if (universe.isEmpty()) {
                throw new IllegalArgumentException("팩터 전략이 조건에 맞는 종목을 찾지 못했습니다. "
                        + "DART_OPEN_API_KEY 설정 여부를 확인하세요.");
            }
            log.info("Factor strategy {} pre-selected {} stocks for backtest", strategy.type(), universe.size());
        }

        // 가격 이력 수집. 모멘텀 계산에 12개월이 필요하므로 startDate보다 14개월 먼저부터 로드.
        LocalDate fetchStart = request.getStartDate().minusMonths(14);
        Map<String, Map<LocalDate, BigDecimal>> prices = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        boolean dynamic = strategy.dynamicUniverse();
        for (QuantAsset a : dedupeBySymbol(universe)) {
            try {
                MarketSymbol sym = a.getExchange() != null && !a.getExchange().isBlank()
                        ? MarketSymbol.of(a.getMarket(), a.getSymbol()).withExchange(a.getExchange())
                        : MarketSymbol.of(a.getMarket(), a.getSymbol());
                KisMarketDataService.ChartResult chart = marketData.getChartData(sym, fetchStart);
                if (chart.priceHistory().isEmpty()) {
                    if (dynamic) {
                        log.warn("Skipping {} ({}): empty price history", a.getSymbol(), a.getName());
                        continue;
                    }
                    throw new RuntimeException("가격 이력 없음");
                }
                Map<LocalDate, BigDecimal> map = new TreeMap<>();
                for (PricePoint pp : chart.priceHistory()) map.put(pp.getDate(), pp.getClose());
                prices.put(a.getSymbol(), map);
                names.put(a.getSymbol(), a.getName() != null ? a.getName() : chart.name());
            } catch (Exception e) {
                if (dynamic) {
                    log.warn("Skipping {} ({}): {}", a.getSymbol(), a.getName(), e.getMessage());
                    continue;
                }
                throw new RuntimeException(
                        "가격 조회 실패: " + a.getSymbol() + " (" + a.getName() + "): " + e.getMessage(), e);
            }
        }

        List<LocalDate> simDates = dynamic
                ? findSimDatesUnion(prices, request.getStartDate())
                : findSimDates(prices, request.getStartDate());
        if (simDates.size() < 30) {
            throw new IllegalArgumentException("시뮬레이션 가능한 거래일이 부족합니다 (" + simDates.size() + "일). "
                    + "시작일을 더 과거로 하거나 자산 유니버스를 확인하세요.");
        }

        QuantStrategy.StrategyParams params = new QuantStrategy.StrategyParams(
                request.getTopN(), request.getMomentumMonths(), request.getSmaMonths());

        // 초기 상태: 전액 현금
        BigDecimal cash = request.getInvestmentAmount();
        Map<String, BigDecimal> units = new LinkedHashMap<>();

        List<PortfolioBacktestResult.PortfolioValuePoint> valueHistory = new ArrayList<>();
        List<QuantBacktestResult.RebalanceEvent> rebalances = new ArrayList<>();
        LocalDate lastRebalance = null;
        int historySampleStep = Math.max(1, simDates.size() / 500);

        // 강제 초회 리밸런싱: 시뮬레이션 시작일에 무조건 실행
        boolean firstDone = false;

        for (int i = 0; i < simDates.size(); i++) {
            LocalDate today = simDates.get(i);

            boolean rebToday = !firstDone || strategy.shouldRebalance(lastRebalance, today, simDates);
            if (rebToday) {
                // 현재 평가금액 = cash + 보유 유닛 평가
                BigDecimal totalValue = cash;
                for (var e : units.entrySet()) {
                    BigDecimal p = QuantIndicators.closestPriceAtOrBefore(prices.get(e.getKey()), today);
                    if (p != null) totalValue = totalValue.add(e.getValue().multiply(p));
                }

                QuantSignal signal = strategy.generateSignal(universe, prices, today, params);

                // 새 비중으로 유닛 재계산
                Map<String, BigDecimal> newUnits = new LinkedHashMap<>();
                BigDecimal invested = BigDecimal.ZERO;
                for (var w : signal.getTargetWeights().entrySet()) {
                    BigDecimal price = QuantIndicators.closestPriceAtOrBefore(prices.get(w.getKey()), today);
                    if (price == null || price.signum() == 0) continue;
                    BigDecimal alloc = totalValue.multiply(w.getValue());
                    newUnits.put(w.getKey(), alloc.divide(price, 10, RoundingMode.HALF_UP));
                    invested = invested.add(alloc);
                }
                units = newUnits;
                cash = totalValue.subtract(invested);

                rebalances.add(QuantBacktestResult.RebalanceEvent.builder()
                        .date(today)
                        .weights(toDisplayWeights(signal.getTargetWeights()))
                        .note(signal.getRationale())
                        .build());
                lastRebalance = today;
                firstDone = true;
            }

            // 평가
            BigDecimal val = cash;
            for (var e : units.entrySet()) {
                BigDecimal p = QuantIndicators.closestPriceAtOrBefore(prices.get(e.getKey()), today);
                if (p != null) val = val.add(e.getValue().multiply(p));
            }
            if (i % historySampleStep == 0 || i == simDates.size() - 1) {
                valueHistory.add(PortfolioBacktestResult.PortfolioValuePoint.builder()
                        .date(today)
                        .value(val.setScale(2, RoundingMode.HALF_UP))
                        .invested(request.getInvestmentAmount().setScale(2, RoundingMode.HALF_UP))
                        .build());
            }
        }

        LocalDate endDate = simDates.get(simDates.size() - 1);
        BigDecimal finalValue = valueHistory.get(valueHistory.size() - 1).getValue();
        BigDecimal profitLoss = finalValue.subtract(request.getInvestmentAmount());
        BigDecimal returnPct = profitLoss
                .divide(request.getInvestmentAmount(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        RiskMetrics metrics = computeRiskMetrics(valueHistory, request.getInvestmentAmount(),
                simDates.get(0), endDate);

        // 벤치마크: KOSPI200 (069500) — 자산이 이미 KR ETF면 중복 방지
        List<BenchmarkResult> benchmarks = null;
        if (request.isBenchmarkEnabled()) {
            benchmarks = buildBenchmark(request.getInvestmentAmount(), simDates.get(0), endDate);
        }

        return QuantBacktestResult.builder()
                .strategyType(request.getStrategyType())
                .strategyName(strategy.displayName())
                .startDate(simDates.get(0))
                .endDate(endDate)
                .initialAmount(request.getInvestmentAmount().setScale(2, RoundingMode.HALF_UP))
                .finalValue(finalValue)
                .profitLoss(profitLoss.setScale(2, RoundingMode.HALF_UP))
                .totalReturnPercent(returnPct)
                .riskMetrics(metrics)
                .rebalances(rebalances)
                .valueHistory(valueHistory)
                .benchmarks(benchmarks)
                .universe(universe)
                .build();
    }

    private List<BenchmarkResult> buildBenchmark(BigDecimal invested, LocalDate start, LocalDate end) {
        List<BenchmarkResult> out = new ArrayList<>();
        try {
            MarketSymbol ks = MarketSymbol.of(AssetType.KR_STOCK, "069500");
            KisMarketDataService.ChartResult chart = marketData.getChartData(ks, start);
            if (chart.priceHistory().isEmpty()) return out;
            Map<LocalDate, BigDecimal> m = new TreeMap<>();
            for (PricePoint pp : chart.priceHistory()) m.put(pp.getDate(), pp.getClose());

            BigDecimal firstPrice = QuantIndicators.closestPriceAtOrBefore(m, start);
            BigDecimal lastPrice = QuantIndicators.closestPriceAtOrBefore(m, end);
            if (firstPrice == null || lastPrice == null) return out;
            BigDecimal units = invested.divide(firstPrice, 10, RoundingMode.HALF_UP);
            BigDecimal finalVal = units.multiply(lastPrice);

            long days = ChronoUnit.DAYS.between(start, end);
            double years = days / 365.25;
            double total = finalVal.subtract(invested)
                    .divide(invested, 6, RoundingMode.HALF_UP).doubleValue() * 100;
            double cagr = years > 0
                    ? (Math.pow(finalVal.doubleValue() / invested.doubleValue(), 1.0 / years) - 1) * 100 : 0;

            List<PortfolioBacktestResult.PortfolioValuePoint> hist = new ArrayList<>();
            List<LocalDate> dates = m.keySet().stream().filter(d -> !d.isBefore(start) && !d.isAfter(end)).toList();
            int step = Math.max(1, dates.size() / 200);
            for (int i = 0; i < dates.size(); i += step) {
                LocalDate d = dates.get(i);
                hist.add(PortfolioBacktestResult.PortfolioValuePoint.builder()
                        .date(d)
                        .value(units.multiply(m.get(d)).setScale(2, RoundingMode.HALF_UP))
                        .invested(invested.setScale(2, RoundingMode.HALF_UP))
                        .build());
            }
            out.add(BenchmarkResult.builder()
                    .name("KODEX 200 (벤치)")
                    .symbol("069500")
                    .totalReturn(BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP))
                    .cagr(BigDecimal.valueOf(cagr).setScale(2, RoundingMode.HALF_UP))
                    .finalValue(finalVal.setScale(2, RoundingMode.HALF_UP))
                    .valueHistory(hist)
                    .build());
        } catch (Exception e) {
            log.warn("Benchmark failed: {}", e.getMessage());
        }
        return out;
    }

    // ========== RISK METRICS (간단화 버전, PortfolioService와 유사 공식) ==========

    private RiskMetrics computeRiskMetrics(List<PortfolioBacktestResult.PortfolioValuePoint> hist,
                                           BigDecimal invested, LocalDate start, LocalDate end) {
        if (hist.size() < 2) return RiskMetrics.builder().build();
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < hist.size(); i++) {
            double prev = hist.get(i - 1).getValue().doubleValue();
            double cur = hist.get(i).getValue().doubleValue();
            if (prev > 0) returns.add((cur - prev) / prev);
        }
        double meanDaily = returns.stream().mapToDouble(d -> d).average().orElse(0);
        double var = returns.stream().mapToDouble(r -> Math.pow(r - meanDaily, 2)).average().orElse(0);
        double dailyStd = Math.sqrt(var);
        double downVar = returns.stream().filter(r -> r < 0).mapToDouble(r -> r * r).average().orElse(0);
        double downStd = Math.sqrt(downVar);

        double annualReturn = meanDaily * TRADING_DAYS_PER_YEAR * 100;
        double annualVol = dailyStd * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100;
        double rf = RISK_FREE_RATE.doubleValue() * 100;
        double sharpe = annualVol > 0 ? (annualReturn - rf) / annualVol : 0;
        double annualDown = downStd * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100;
        double sortino = annualDown > 0 ? (annualReturn - rf) / annualDown : 0;

        double peak = hist.get(0).getValue().doubleValue();
        double maxDD = 0;
        int ddStartIdx = 0, ddEndIdx = 0, peakIdx = 0;
        for (int i = 1; i < hist.size(); i++) {
            double v = hist.get(i).getValue().doubleValue();
            if (v > peak) { peak = v; peakIdx = i; }
            double dd = (v - peak) / peak;
            if (dd < maxDD) { maxDD = dd; ddStartIdx = peakIdx; ddEndIdx = i; }
        }

        double lastVal = hist.get(hist.size() - 1).getValue().doubleValue();
        double totalReturn = invested.doubleValue() > 0
                ? ((lastVal - invested.doubleValue()) / invested.doubleValue()) * 100 : 0;
        long days = ChronoUnit.DAYS.between(start, end);
        double years = days / 365.25;
        double cagr = years > 0 && invested.doubleValue() > 0
                ? (Math.pow(lastVal / invested.doubleValue(), 1.0 / years) - 1) * 100 : 0;

        return RiskMetrics.builder()
                .sharpeRatio(BigDecimal.valueOf(sharpe).setScale(2, RoundingMode.HALF_UP))
                .sortinoRatio(BigDecimal.valueOf(sortino).setScale(2, RoundingMode.HALF_UP))
                .maxDrawdown(BigDecimal.valueOf(maxDD * 100).setScale(2, RoundingMode.HALF_UP))
                .maxDrawdownStart(hist.get(ddStartIdx).getDate())
                .maxDrawdownEnd(hist.get(ddEndIdx).getDate())
                .annualVolatility(BigDecimal.valueOf(annualVol).setScale(2, RoundingMode.HALF_UP))
                .annualReturn(BigDecimal.valueOf(annualReturn).setScale(2, RoundingMode.HALF_UP))
                .totalReturn(BigDecimal.valueOf(totalReturn).setScale(2, RoundingMode.HALF_UP))
                .cagr(BigDecimal.valueOf(cagr).setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    // ========== HELPERS ==========

    /** 모든 자산에 공통으로 존재하는 거래일 + startDate 이후. */
    private List<LocalDate> findSimDates(Map<String, Map<LocalDate, BigDecimal>> prices, LocalDate startDate) {
        Set<LocalDate> common = null;
        for (var e : prices.entrySet()) {
            Set<LocalDate> dates = e.getValue().keySet().stream()
                    .filter(d -> !d.isBefore(startDate))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (common == null) common = dates;
            else common.retainAll(dates);
        }
        if (common == null) return List.of();
        return common.stream().sorted().toList();
    }

    /** 동적 유니버스(SPAC 등): 적어도 한 자산에 존재하는 거래일의 합집합. */
    private List<LocalDate> findSimDatesUnion(Map<String, Map<LocalDate, BigDecimal>> prices, LocalDate startDate) {
        Set<LocalDate> union = new TreeSet<>();
        for (var e : prices.values()) {
            for (LocalDate d : e.keySet()) {
                if (!d.isBefore(startDate)) union.add(d);
            }
        }
        return new ArrayList<>(union);
    }

    private static Map<String, BigDecimal> toDisplayWeights(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal invested = BigDecimal.ZERO;
        for (var e : raw.entrySet()) {
            BigDecimal pct = e.getValue().multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            out.put(e.getKey(), pct);
            invested = invested.add(e.getValue());
        }
        BigDecimal cashWeight = BigDecimal.ONE.subtract(invested);
        if (cashWeight.compareTo(new BigDecimal("0.001")) > 0) {
            out.put("CASH", cashWeight.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return out;
    }

    private static List<QuantAsset> dedupeBySymbol(List<QuantAsset> in) {
        Map<String, QuantAsset> m = new LinkedHashMap<>();
        for (QuantAsset a : in) m.putIfAbsent(a.getSymbol(), a);
        return List.copyOf(m.values());
    }

    private void validate(QuantBacktestRequest r) {
        if (r.getStrategyType() == null) throw new IllegalArgumentException("strategyType is required");
        if (r.getStartDate() == null) throw new IllegalArgumentException("startDate is required");
        if (r.getStartDate().isAfter(LocalDate.now())) throw new IllegalArgumentException("startDate must not be in the future");
        if (r.getInvestmentAmount() == null || r.getInvestmentAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("investmentAmount must be positive");
    }
}

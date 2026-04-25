package com.backtesting.service.elw;

import com.backtesting.config.ElwProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.elw.ElwModels.ElwContract;
import com.backtesting.model.elw.ElwModels.ElwIvRow;
import com.backtesting.model.elw.ElwModels.ElwSkewResponse;
import com.backtesting.model.elw.ElwModels.OptionType;
import com.backtesting.model.elw.ElwModels.SkewPoint;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.MarketSymbol;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ELW 체인 조회 + IV 병렬 역산 + 풋-콜 스큐 집계.
 *
 * Fault tolerance 4중:
 *  1) 체인 조회는 ElwCircuitBreaker 로 보호 — OPEN 상태 시 즉시 degraded 응답
 *  2) 기초자산 시세 결측 시 전체 중단 없이 degraded 응답
 *  3) 개별 IV 계산은 예외 아닌 Status 로 반환 — 1개 실패가 나머지 오염 안 함
 *  4) 워커 풀 크기로 동시성 상한 — KIS 레이트리미터와 중첩 보호
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElwSkewService {

    private final ElwProperties props;
    private final ElwChainProvider chainProvider;
    private final ElwCircuitBreaker breaker;
    private final KisMarketDataService marketData;
    /** ElwMetrics 는 선택적 — 단위 테스트에서 생략 가능. */
    @Autowired(required = false)
    private ElwMetrics metrics;

    private ExecutorService executor;

    @PostConstruct
    void init() {
        int n = Math.max(1, props.getConcurrency());
        executor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "elw-iv-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    public ElwSkewResponse scan(String underlyingCode) {
        long startNanos = System.nanoTime();
        if (!chainProvider.isAvailable()) {
            return recordAndReturn(degraded(underlyingCode, 0,
                    "ELW provider 미구성 — application.yml의 elw.enabled/chain-url/chain-tr-id 설정 필요"), startNanos);
        }

        Optional<List<ElwContract>> chainOpt = breaker.protect(() -> chainProvider.fetchChain(underlyingCode));
        if (chainOpt.isEmpty()) {
            return recordAndReturn(
                    degraded(underlyingCode, 0, "circuit " + breaker.state() + " — 체인 조회 스킵 (쿨다운 후 재시도)"),
                    startNanos);
        }
        List<ElwContract> chain = chainOpt.get();
        if (chain.isEmpty()) {
            return recordAndReturn(degraded(underlyingCode, 0, "체인 응답이 비어 있음 — 기초자산 코드 확인"), startNanos);
        }

        double underlyingPrice;
        try {
            underlyingPrice = fetchUnderlyingPrice(underlyingCode).doubleValue();
        } catch (Exception e) {
            log.warn("Underlying price fetch failed for {}: {}", underlyingCode, e.getMessage());
            return recordAndReturn(
                    degraded(underlyingCode, chain.size(), "기초자산 시세 조회 실패: " + e.getMessage()),
                    startNanos);
        }

        LocalDate today = LocalDate.now();
        List<CompletableFuture<ElwIvRow>> futures = new ArrayList<>(chain.size());
        for (ElwContract c : chain) {
            futures.add(CompletableFuture.supplyAsync(() -> computeRow(c, underlyingPrice, today), executor));
        }

        List<ElwIvRow> rows = new ArrayList<>(chain.size());
        long timeoutMs = Math.max(100, props.getIvTimeoutMs());
        for (CompletableFuture<ElwIvRow> f : futures) {
            try {
                rows.add(f.get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                f.cancel(true);
                log.debug("IV task timeout/failed: {}", e.getMessage());
            }
        }
        rows.removeIf(Objects::isNull);

        if (metrics != null) {
            for (ElwIvRow r : rows) metrics.recordIvOutcome(r.ivStatus());
        }

        int converged = (int) rows.stream().filter(r -> "CONVERGED".equals(r.ivStatus())).count();
        int failed = rows.size() - converged;

        ElwSkewResponse resp = new ElwSkewResponse(
                underlyingCode,
                breaker.state().name(),
                chain.size(),
                converged,
                failed,
                rows,
                buildSkew(rows),
                Instant.now(),
                null
        );
        if (metrics != null) {
            metrics.scanTimer("ok").record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
        return resp;
    }

    private ElwSkewResponse recordAndReturn(ElwSkewResponse degraded, long startNanos) {
        if (metrics != null) {
            metrics.recordDegraded(degraded.degradedReason());
            metrics.scanTimer("degraded").record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
        return degraded;
    }

    private ElwIvRow computeRow(ElwContract c, double s, LocalDate today) {
        double t = ChronoUnit.DAYS.between(today, c.expiry()) / 365.0;
        if (t <= 0) {
            return new ElwIvRow(c.symbol(), c.name(), c.type(), c.strike(), c.expiry(),
                    null, "EXPIRED", 0, s, c.marketPrice(), c.asOf(), "at or past expiry");
        }
        boolean isCall = c.type() == OptionType.CALL;
        BlackScholesIv.IvResult r = BlackScholesIv.solve(
                c.marketPrice(), s, c.strike(), t,
                props.getRiskFreeRate(), props.getDividendYield(), isCall);
        return new ElwIvRow(
                c.symbol(), c.name(), c.type(), c.strike(), c.expiry(),
                r.value(), r.status().name(), r.iterations(),
                s, c.marketPrice(), c.asOf(), r.note()
        );
    }

    private List<SkewPoint> buildSkew(List<ElwIvRow> rows) {
        Map<String, double[]> agg = new LinkedHashMap<>();
        Map<String, Map.Entry<LocalDate, Double>> keyIndex = new LinkedHashMap<>();
        for (ElwIvRow r : rows) {
            if (r.iv() == null) continue;
            String key = r.expiry() + "|" + r.strike();
            keyIndex.putIfAbsent(key, Map.entry(r.expiry(), r.strike()));
            double[] pair = agg.computeIfAbsent(key, x -> new double[]{Double.NaN, Double.NaN});
            if (r.type() == OptionType.CALL) pair[0] = r.iv();
            else pair[1] = r.iv();
        }
        List<SkewPoint> out = new ArrayList<>();
        for (var e : agg.entrySet()) {
            var k = keyIndex.get(e.getKey());
            double call = e.getValue()[0], put = e.getValue()[1];
            Double sk = (Double.isNaN(call) || Double.isNaN(put)) ? null : put - call;
            out.add(new SkewPoint(k.getKey(), k.getValue(),
                    Double.isNaN(call) ? null : call,
                    Double.isNaN(put) ? null : put, sk));
        }
        out.sort(Comparator.comparing(SkewPoint::expiry).thenComparingDouble(SkewPoint::strike));
        return out;
    }

    private BigDecimal fetchUnderlyingPrice(String code) throws Exception {
        MarketSymbol sym = MarketSymbol.of(AssetType.KR_STOCK, code);
        var chart = marketData.getChartData(sym, LocalDate.now().minusDays(10));
        if (chart.priceHistory().isEmpty()) throw new RuntimeException("no price history");
        return chart.priceHistory().get(chart.priceHistory().size() - 1).getClose();
    }

    private ElwSkewResponse degraded(String code, int chainCount, String reason) {
        return new ElwSkewResponse(code, breaker.state().name(), chainCount, 0, 0,
                List.of(), List.of(), Instant.now(), reason);
    }
}

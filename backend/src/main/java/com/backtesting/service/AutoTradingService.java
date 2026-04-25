package com.backtesting.service;

import com.backtesting.model.AssetType;
import com.backtesting.model.LivePrice;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.MarketSymbol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 감시종목 실시간 가격 폴링.
 *
 * 재설계 포인트:
 *  - 기존 refresh 는 직렬 HTTP. 100+ 종목 × 3rps 한도면 한 사이클 = 33s → 30s 폴링 사이클 뒷걸음질.
 *  - 신규: kisExecutor 위에서 CompletableFuture 병렬. KisRateLimiter 가 per-bucket 공정성 보장.
 *  - 사이클 전체에 60s 소프트 데드라인 — 초과분은 드롭하고 다음 사이클에 재시도.
 *  - 부분 실패(한두 종목 에러)는 전체를 망치지 않고 성공분만 push.
 */
@Slf4j
@Service
public class AutoTradingService {

    private static final long CYCLE_TIMEOUT_SEC = 60;

    private final KisMarketDataService marketData;
    private final SimpMessagingTemplate messaging;
    private final ExecutorService kisExecutor;

    /** key = market:code, value = symbol */
    private final Map<String, MarketSymbol> watchlist = new ConcurrentHashMap<>();
    private final Map<String, LivePrice> latest = new ConcurrentHashMap<>();

    public AutoTradingService(KisMarketDataService marketData,
                               SimpMessagingTemplate messaging,
                               @Qualifier("kisExecutor") ExecutorService kisExecutor) {
        this.marketData = marketData;
        this.messaging = messaging;
        this.kisExecutor = kisExecutor;
    }

    public void subscribe(AssetType market, String code, String exchange) {
        MarketSymbol sym = MarketSymbol.of(market, code);
        if (exchange != null && !exchange.isBlank()) sym = sym.withExchange(exchange);
        watchlist.put(key(sym), sym);
        refresh(List.of(sym));
    }

    public void unsubscribe(AssetType market, String code) {
        MarketSymbol sym = MarketSymbol.of(market, code);
        watchlist.remove(key(sym));
        latest.remove(key(sym));
    }

    public List<LivePrice> getQuotes() {
        return new ArrayList<>(latest.values());
    }

    public Collection<MarketSymbol> getWatchlist() {
        return watchlist.values();
    }

    public void refreshAll() {
        refresh(new ArrayList<>(watchlist.values()));
    }

    /** 폴링: 30초 간격. 모의 환경에서 빈 watchlist 면 no-op. */
    @Scheduled(fixedRate = 30000, initialDelay = 15000)
    public void scheduledPoll() {
        if (watchlist.isEmpty()) return;
        refresh(new ArrayList<>(watchlist.values()));
    }

    /**
     * 병렬 fetch — bulkhead executor 위에서 per-symbol future. 사이클 데드라인 초과 시 타임아웃.
     * 스케줄 스레드(Spring task scheduler)를 길게 붙잡지 않도록 한다.
     */
    private void refresh(List<MarketSymbol> symbols) {
        if (symbols.isEmpty()) return;
        long t0 = System.nanoTime();

        List<CompletableFuture<LivePrice>> futures = new ArrayList<>(symbols.size());
        for (MarketSymbol sym : symbols) {
            CompletableFuture<LivePrice> f;
            try {
                f = CompletableFuture.supplyAsync(() -> fetchOne(sym), kisExecutor);
            } catch (RejectedExecutionException rej) {
                log.warn("kisExecutor saturated — dropping quote {} this cycle", sym.code());
                continue;
            }
            futures.add(f);
        }
        if (futures.isEmpty()) return;

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            all.get(CYCLE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            long running = futures.stream().filter(f -> !f.isDone()).count();
            log.warn("quote refresh cycle timed out after {}s — {} futures pending, cancelling",
                    CYCLE_TIMEOUT_SEC, running);
            futures.forEach(f -> f.cancel(true));
        } catch (Exception ignored) {
            // 개별 실패는 아래 collect 단계에서 무시.
        }

        List<LivePrice> updated = new ArrayList<>(futures.size());
        for (CompletableFuture<LivePrice> f : futures) {
            if (!f.isDone() || f.isCompletedExceptionally() || f.isCancelled()) continue;
            try {
                LivePrice p = f.getNow(null);
                if (p != null) updated.add(p);
            } catch (Exception ignored) {}
        }

        if (!updated.isEmpty()) {
            messaging.convertAndSend("/topic/quotes", updated);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.debug("refresh cycle: requested={} updated={} elapsedMs={}",
                symbols.size(), updated.size(), elapsedMs);
    }

    private LivePrice fetchOne(MarketSymbol sym) {
        try {
            var quote = marketData.getQuote(sym);
            LivePrice p = LivePrice.builder()
                    .symbol(sym.code())
                    .name(quote.name())
                    .price(quote.price().setScale(2, RoundingMode.HALF_UP))
                    .previousClose(quote.previousClose().setScale(2, RoundingMode.HALF_UP))
                    .change(quote.change().setScale(2, RoundingMode.HALF_UP))
                    .changePercent(quote.changePercent().setScale(2, RoundingMode.HALF_UP))
                    .currency(quote.currency())
                    .updatedAt(LocalDateTime.now())
                    .build();
            latest.put(key(sym), p);
            return p;
        } catch (Exception e) {
            log.warn("Quote fetch failed for {}:{} - {}", sym.market(), sym.code(), e.getMessage());
            return null;
        }
    }

    private String key(MarketSymbol sym) {
        return sym.market() + ":" + sym.code();
    }
}

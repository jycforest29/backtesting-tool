package com.backtesting.service.quant;

import com.backtesting.config.KisProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.BalanceResult;
import com.backtesting.model.OrderRequest;
import com.backtesting.model.OrderResult;
import com.backtesting.model.PricePoint;
import com.backtesting.model.quant.*;
import com.backtesting.persistence.StrategyConfigEntity;
import com.backtesting.persistence.StrategyConfigRepository;
import com.backtesting.persistence.StrategyExecutionEntity;
import com.backtesting.persistence.StrategyExecutionRepository;
import com.backtesting.service.DailyLossGuard;
import com.backtesting.service.EmailService;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.KisTradingService;
import com.backtesting.service.kis.MarketSymbol;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 강환국 퀀트 전략을 실계좌에 실행.
 *
 * 흐름:
 *   1. 월말 스케줄러(@Scheduled) 또는 수동 트리거 → runStrategy(type, kind)
 *   2. 최근 14개월 가격 수집 → 시그널 생성
 *   3. KIS 잔고 조회 → 이 전략이 관리하는 심볼의 현재 보유량 계산
 *   4. 목표 비중 vs 현재 비중 → 델타 주문 (SELL 먼저, 그 다음 BUY)
 *   5. 실행 로그 저장 + 메일 알림
 *
 * 안전장치:
 *   - DailyLossGuard 준수 (BUY 차단 시 해당 주문 스킵, 로그 남김)
 *   - 국내 ETF만 취급 (해외 ETF 혼합은 현 단계 미지원)
 *   - MARKET 주문 사용 (정확한 체결가는 사후 확인)
 *   - 주문 실패해도 다음 주문 진행 (best-effort rebalancing)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuantExecutionService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_EXECUTION_LOG_PER_STRATEGY = 50;

    private final KisMarketDataService marketData;
    private final KisTradingService kisTrading;
    private final KisProperties kisProps;
    private final DailyLossGuard lossGuard;
    private final EmailService emailService;
    private final QuantStrategyRegistry registry;
    private final StrategyConfigRepository configRepo;
    private final StrategyExecutionRepository execRepo;

    /** 전략별 상태(활성화 여부, 할당 금액, 최근 로그). */
    private final Map<QuantStrategyType, MutableState> states = new ConcurrentHashMap<>();

    /**
     * 기동 복구 — DB에서 전략 설정 로드.
     * enabled=true인 전략은 즉시 활성 상태로 메모리 반영.
     * 실행 이력은 필요 시점에 lazy 로드.
     */
    @PostConstruct
    void restoreFromDb() {
        try {
            List<StrategyConfigEntity> all = configRepo.findAll();
            for (StrategyConfigEntity e : all) {
                if (!registry.isKnown(e.getStrategyType())) continue;
                MutableState s = new MutableState(e.getStrategyType());
                s.enabled = e.isEnabled();
                s.allocated = e.getAllocatedAmount();
                s.universe = e.getCustomUniverse();
                s.topN = e.getTopN();
                s.momentumMonths = e.getMomentumMonths();
                s.smaMonths = e.getSmaMonths();
                s.lastRebalanceDate = e.getLastRebalanceDate();
                s.lastRunAt = e.getLastRunAt();
                s.lastSignal = e.getLastSignal();
                s.currentWeights = e.getCurrentWeights() != null
                        ? new LinkedHashMap<>(e.getCurrentWeights()) : new LinkedHashMap<>();
                states.put(e.getStrategyType(), s);
            }
            log.info("Quant strategies restored: {} (enabled={})",
                    all.size(), all.stream().filter(StrategyConfigEntity::isEnabled).count());
        } catch (Exception e) {
            log.error("Quant strategy restore failed: {}", e.getMessage(), e);
        }
    }

    /** 메모리 상태를 DB에 반영. 예외는 로깅만. */
    private void persistConfig(MutableState s) {
        try {
            StrategyConfigEntity e = StrategyConfigEntity.builder()
                    .strategyType(s.type)
                    .enabled(s.enabled)
                    .allocatedAmount(s.allocated)
                    .customUniverse(s.universe)
                    .topN(s.topN)
                    .momentumMonths(s.momentumMonths)
                    .smaMonths(s.smaMonths)
                    .lastRebalanceDate(s.lastRebalanceDate)
                    .lastRunAt(s.lastRunAt)
                    .lastSignal(s.lastSignal)
                    .currentWeights(s.currentWeights)
                    .build();
            configRepo.save(e);
        } catch (Exception e) {
            log.error("Strategy config persist failed type={}: {}", s.type, e.getMessage());
        }
    }

    // ========== 상태 조회 / 변경 ==========

    public synchronized QuantStrategyState enable(QuantStrategyType type, QuantEnableRequest req) {
        if (req.getAllocatedAmount() == null || req.getAllocatedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("allocatedAmount는 양수여야 합니다");
        }
        MutableState s = states.computeIfAbsent(type, MutableState::new);
        s.enabled = true;
        s.allocated = req.getAllocatedAmount();
        s.universe = req.getCustomUniverse();
        s.topN = req.getTopN();
        s.momentumMonths = req.getMomentumMonths();
        s.smaMonths = req.getSmaMonths();
        persistConfig(s);
        log.info("Quant strategy ENABLED: type={} allocated={}", type, s.allocated);
        return snapshot(s);
    }

    public synchronized QuantStrategyState disable(QuantStrategyType type) {
        MutableState s = states.computeIfAbsent(type, MutableState::new);
        s.enabled = false;
        persistConfig(s);
        log.info("Quant strategy DISABLED: type={}", type);
        return snapshot(s);
    }

    public QuantStrategyState getState(QuantStrategyType type) {
        return snapshot(states.computeIfAbsent(type, MutableState::new));
    }

    public List<QuantStrategyState> listStates() {
        List<QuantStrategyState> out = new ArrayList<>();
        for (QuantStrategy s : registry.all()) {
            out.add(snapshot(states.computeIfAbsent(s.type(), MutableState::new)));
        }
        return out;
    }

    // ========== 스케줄러 ==========

    /**
     * 매 거래일 15:25 KST(장 마감 10분 전)에 활성화된 전략을 점검.
     * 각 전략이 shouldRebalance(today)를 true로 반환하면 실행.
     */
    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledCheck() {
        if (!kisProps.isConfigured()) {
            log.debug("KIS not configured, skipping scheduled quant check");
            return;
        }
        for (Map.Entry<QuantStrategyType, MutableState> e : states.entrySet()) {
            if (!e.getValue().enabled) continue;
            try {
                runStrategy(e.getKey(), QuantExecutionLog.ExecutionKind.SCHEDULED, true);
            } catch (Exception ex) {
                log.error("Scheduled quant run failed for {}: {}", e.getKey(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * 수동 트리거. checkRebalanceTiming=false 면 시그 생성 + 주문까지 강제 실행.
     * UI의 "지금 리밸런싱" 버튼에서 호출.
     */
    public synchronized QuantExecutionLog runStrategy(QuantStrategyType type,
                                                      QuantExecutionLog.ExecutionKind kind,
                                                      boolean respectTiming) {
        QuantStrategy strategy = registry.get(type);
        MutableState state = states.computeIfAbsent(type, MutableState::new);
        if (!state.enabled && kind == QuantExecutionLog.ExecutionKind.SCHEDULED) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);
        List<QuantAsset> universe = state.universe != null && !state.universe.isEmpty()
                ? state.universe : strategy.defaultUniverse();

        // 가격 로드 (실매매는 최근 데이터만 필요 — 14개월)
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate fetchStart = today.minusMonths(14);
        Map<String, Map<LocalDate, BigDecimal>> prices;
        List<LocalDate> datesIntersection;
        try {
            prices = loadPrices(universe, fetchStart);
            datesIntersection = intersectDates(prices);
        } catch (Exception e) {
            return logError(state, type, kind, now, "가격 조회 실패: " + e.getMessage());
        }
        if (datesIntersection.isEmpty()) {
            return logError(state, type, kind, now, "공통 거래일 데이터 없음");
        }

        LocalDate latestDataDate = datesIntersection.get(datesIntersection.size() - 1);

        // 타이밍 체크 (스케줄 실행 시만)
        if (respectTiming && !strategy.shouldRebalance(state.lastRebalanceDate, today, datesIntersection)) {
            log.debug("Strategy {} not scheduled to rebalance today", type);
            return null;
        }

        // === 중복 리밸런싱 방지 (idempotency) ===
        // 스케줄러가 같은 날 중복 발화되거나, DB에 이미 오늘 SCHEDULED 실행이 있으면 스킵.
        // DRY_RUN과 MANUAL(force)은 제외 — 사용자가 의도한 명시적 재실행은 허용.
        if (kind == QuantExecutionLog.ExecutionKind.SCHEDULED
                && state.lastRebalanceDate != null
                && state.lastRebalanceDate.equals(today)) {
            log.info("Strategy {} already rebalanced today ({}), skipping duplicate schedule",
                    type, today);
            return null;
        }

        QuantStrategy.StrategyParams params = new QuantStrategy.StrategyParams(
                state.topN, state.momentumMonths, state.smaMonths);
        QuantSignal signal;
        try {
            signal = strategy.generateSignal(universe, prices, latestDataDate, params);
        } catch (Exception e) {
            return logError(state, type, kind, now, "시그널 생성 실패: " + e.getMessage());
        }

        List<QuantExecutionLog.OrderOutcome> outcomes = new ArrayList<>();
        String err = null;
        if (kind == QuantExecutionLog.ExecutionKind.DRY_RUN) {
            // 주문 없이 시그널만
            for (var w : signal.getTargetWeights().entrySet()) {
                String name = nameFrom(universe, w.getKey());
                outcomes.add(QuantExecutionLog.OrderOutcome.builder()
                        .symbol(w.getKey()).name(name)
                        .side("SIGNAL").quantity(0).price(BigDecimal.ZERO)
                        .success(true)
                        .message("드라이런: 목표비중 " + w.getValue().multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP) + "%")
                        .build());
            }
        } else {
            try {
                outcomes = executeRebalance(signal, universe, state.allocated);
            } catch (Exception e) {
                err = "주문 실행 실패: " + e.getMessage();
                log.error("Rebalance failed", e);
            }
        }

        QuantExecutionLog logEntry = QuantExecutionLog.builder()
                .executedAt(now)
                .strategyType(type)
                .kind(kind)
                .signal(signal)
                .orders(outcomes)
                .errorMessage(err)
                .build();
        state.appendLog(logEntry);
        persistExecution(logEntry);
        if (kind != QuantExecutionLog.ExecutionKind.DRY_RUN && err == null) {
            state.lastRebalanceDate = today;
            state.lastRunAt = now;
            state.currentWeights = new LinkedHashMap<>(signal.getTargetWeights());
            state.lastSignal = signal;
            persistConfig(state);
        }

        // 메일 알림 (격리)
        try {
            sendExecutionEmail(type, strategy, logEntry);
        } catch (Exception mailEx) {
            log.warn("Execution email failed (non-fatal): {}", mailEx.getMessage());
        }
        return logEntry;
    }

    /** 실행 이력 DB 저장 (UI 조회용). 실패해도 실행 흐름엔 영향 없음. */
    private void persistExecution(QuantExecutionLog e) {
        try {
            execRepo.save(StrategyExecutionEntity.fromDomain(e));
        } catch (Exception ex) {
            log.error("Execution persist failed: {}", ex.getMessage());
        }
    }

    // ========== 주문 실행 ==========

    /**
     * 시그널 기반 리밸런싱.
     * 1. 잔고 조회 → 이 전략 유니버스 심볼의 현재 보유량/평가금액
     * 2. 각 심볼 목표 평가금액 = allocated * weight
     * 3. 델타 = 목표 - 현재
     *    - 델타 < 0 : SELL
     *    - 델타 > 0 : BUY
     * 4. SELL 먼저 전부 실행 → 현금 확보 → BUY 실행
     */
    private List<QuantExecutionLog.OrderOutcome> executeRebalance(QuantSignal signal,
                                                                  List<QuantAsset> universe,
                                                                  BigDecimal allocated) {
        BalanceResult balance = kisTrading.getBalance(AssetType.KR_STOCK);
        Map<String, BalanceResult.Holding> holdingsBySymbol = new LinkedHashMap<>();
        for (BalanceResult.Holding h : balance.getHoldings()) {
            holdingsBySymbol.put(h.getSymbol(), h);
        }

        Set<String> universeSymbols = new LinkedHashSet<>();
        for (QuantAsset a : universe) universeSymbols.add(a.getSymbol());

        // 실제로 손댈 심볼 = 목표 비중 > 0 ∪ 현재 보유 중인 유니버스 심볼.
        // 팩터 전략처럼 유니버스가 80종목이어도 대부분은 target=0이고 hold=0이라 스킵 가능.
        Set<String> active = new LinkedHashSet<>();
        for (var w : signal.getTargetWeights().entrySet()) {
            if (w.getValue() != null && w.getValue().signum() > 0) active.add(w.getKey());
        }
        for (String sym : universeSymbols) {
            if (holdingsBySymbol.containsKey(sym)) active.add(sym);
        }

        // 목표 평가금액
        Map<String, BigDecimal> targetAmounts = new LinkedHashMap<>();
        for (String sym : active) {
            BigDecimal w = signal.getTargetWeights().getOrDefault(sym, BigDecimal.ZERO);
            targetAmounts.put(sym, allocated.multiply(w));
        }

        // 현재 평가금액
        Map<String, BigDecimal> currentAmounts = new LinkedHashMap<>();
        Map<String, BigDecimal> currentPrices = new LinkedHashMap<>();
        for (String sym : active) {
            BalanceResult.Holding h = holdingsBySymbol.get(sym);
            if (h != null) {
                currentAmounts.put(sym, h.getEvalAmount() != null ? h.getEvalAmount() : BigDecimal.ZERO);
                if (h.getCurrentPrice() != null) currentPrices.put(sym, h.getCurrentPrice());
            }
        }

        // 현재가 누락된 심볼은 라이브 시세 조회
        for (String sym : active) {
            if (currentPrices.containsKey(sym)) continue;
            try {
                BigDecimal p = marketData.getQuote(MarketSymbol.of(AssetType.KR_STOCK, sym)).price();
                currentPrices.put(sym, p);
            } catch (Exception e) {
                log.warn("시세 조회 실패 {}: {}", sym, e.getMessage());
            }
        }

        Set<String> universeSymbolsForIter = active; // 기존 아래 루프 변경 최소화

        // 델타 계산 + 주문 생성
        List<PendingOrder> sells = new ArrayList<>();
        List<PendingOrder> buys = new ArrayList<>();
        for (String sym : universeSymbolsForIter) {
            BigDecimal target = targetAmounts.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal current = currentAmounts.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal delta = target.subtract(current);
            BigDecimal price = currentPrices.get(sym);
            if (price == null || price.signum() == 0) continue;

            // 최소 주문 금액 = 1주 이상. 델타 절대값이 1주 가격보다 작으면 스킵.
            if (delta.abs().compareTo(price) < 0) continue;

            long qty = delta.abs().divide(price, 0, RoundingMode.DOWN).longValueExact();
            if (qty <= 0) continue;

            PendingOrder po = new PendingOrder();
            po.symbol = sym;
            po.name = nameFrom(universe, sym);
            po.quantity = qty;
            po.priceSnapshot = price;
            if (delta.signum() < 0) { po.side = "SELL"; sells.add(po); }
            else { po.side = "BUY"; buys.add(po); }
        }

        List<QuantExecutionLog.OrderOutcome> outcomes = new ArrayList<>();
        // SELL 먼저
        for (PendingOrder po : sells) outcomes.add(place(po));
        // BUY: 일일 손실가드 체크
        for (PendingOrder po : buys) {
            if (!lossGuard.isBuyAllowed()) {
                outcomes.add(QuantExecutionLog.OrderOutcome.builder()
                        .symbol(po.symbol).name(po.name).side("BUY")
                        .quantity(po.quantity).price(po.priceSnapshot)
                        .success(false)
                        .message("일일 손실 한도 초과로 매수 차단됨")
                        .build());
                continue;
            }
            outcomes.add(place(po));
        }
        return outcomes;
    }

    private QuantExecutionLog.OrderOutcome place(PendingOrder po) {
        OrderRequest req = new OrderRequest();
        req.setMarket(AssetType.KR_STOCK);
        req.setSymbol(po.symbol);
        req.setSide(po.side);
        req.setOrderType("MARKET");
        req.setQuantity(po.quantity);
        // 중복 주문 방지: 제출 전 KIS 미체결 조회로 최근 같은 조건 주문이 이미 있는지 확인.
        // 스케줄러가 동시에 2번 발화 or 네트워크 재시도 시나리오에서 double-send 차단.
        try {
            List<Map<String, Object>> open = kisTrading.getOpenOrders();
            for (Map<String, Object> o : open) {
                String sym = String.valueOf(o.get("symbol"));
                String side = String.valueOf(o.get("side"));   // 01=매도 02=매수
                long qty = o.get("quantity") instanceof Number n ? n.longValue() : 0;
                boolean sideMatch = ("BUY".equals(po.side) && "02".equals(side))
                        || ("SELL".equals(po.side) && "01".equals(side));
                if (sideMatch && sym.equals(po.symbol) && qty == po.quantity) {
                    log.warn("Duplicate order detected — already in KIS open orders. Skipping: {} {} x{}",
                            po.side, po.symbol, po.quantity);
                    return QuantExecutionLog.OrderOutcome.builder()
                            .symbol(po.symbol).name(po.name).side(po.side)
                            .quantity(po.quantity).price(po.priceSnapshot)
                            .success(true).orderNo(String.valueOf(o.get("orderNo")))
                            .message("중복 주문 방지 — 이미 KIS에 동일 조건 미체결 주문 존재")
                            .build();
                }
            }
        } catch (Exception e) {
            log.debug("Pre-order dedupe check failed (continuing): {}", e.getMessage());
        }
        try {
            OrderResult res = kisTrading.placeOrder(req);
            return QuantExecutionLog.OrderOutcome.builder()
                    .symbol(po.symbol).name(po.name).side(po.side)
                    .quantity(po.quantity).price(po.priceSnapshot)
                    .success(res.isSuccess()).orderNo(res.getOrderNo())
                    .message(res.getMessage())
                    .build();
        } catch (Exception e) {
            log.warn("Order failed {} {} {}: {}", po.side, po.symbol, po.quantity, e.getMessage());
            return QuantExecutionLog.OrderOutcome.builder()
                    .symbol(po.symbol).name(po.name).side(po.side)
                    .quantity(po.quantity).price(po.priceSnapshot)
                    .success(false).message("주문 실패: " + e.getMessage())
                    .build();
        }
    }

    private static class PendingOrder {
        String symbol, name, side;
        long quantity;
        BigDecimal priceSnapshot;
    }

    // ========== 가격 로드 ==========

    private Map<String, Map<LocalDate, BigDecimal>> loadPrices(List<QuantAsset> universe, LocalDate from) {
        Map<String, Map<LocalDate, BigDecimal>> out = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (QuantAsset a : universe) {
            if (!seen.add(a.getSymbol())) continue;
            MarketSymbol sym = a.getExchange() != null && !a.getExchange().isBlank()
                    ? MarketSymbol.of(a.getMarket(), a.getSymbol()).withExchange(a.getExchange())
                    : MarketSymbol.of(a.getMarket(), a.getSymbol());
            KisMarketDataService.ChartResult chart = marketData.getChartData(sym, from);
            Map<LocalDate, BigDecimal> map = new TreeMap<>();
            for (PricePoint pp : chart.priceHistory()) map.put(pp.getDate(), pp.getClose());
            out.put(a.getSymbol(), map);
        }
        return out;
    }

    private static List<LocalDate> intersectDates(Map<String, Map<LocalDate, BigDecimal>> prices) {
        Set<LocalDate> common = null;
        for (var e : prices.entrySet()) {
            if (common == null) common = new LinkedHashSet<>(e.getValue().keySet());
            else common.retainAll(e.getValue().keySet());
        }
        if (common == null) return List.of();
        return common.stream().sorted().toList();
    }

    private static String nameFrom(List<QuantAsset> universe, String symbol) {
        return universe.stream().filter(a -> a.getSymbol().equals(symbol))
                .map(QuantAsset::getName).findFirst().orElse(symbol);
    }

    // ========== 상태 스냅샷 ==========

    private QuantStrategyState snapshot(MutableState s) {
        QuantStrategy strat = registry.get(s.type);
        // 메모리 캐시에 이력 없으면 DB에서 최근 20건 로드.
        // 기동 직후 첫 조회나 오래된 전략 재활성화 시 유용.
        List<QuantExecutionLog> recent = new ArrayList<>(s.recent);
        if (recent.isEmpty()) {
            try {
                recent = execRepo
                        .findByStrategyTypeOrderByExecutedAtDesc(s.type, PageRequest.of(0, 20))
                        .stream()
                        .map(StrategyExecutionEntity::toDomain)
                        .toList();
            } catch (Exception e) {
                log.debug("Execution history load failed: {}", e.getMessage());
            }
        }

        return QuantStrategyState.builder()
                .strategyType(s.type)
                .strategyName(strat.displayName())
                .enabled(s.enabled)
                .allocatedAmount(s.allocated)
                .lastRunAt(s.lastRunAt)
                .nextScheduledAt(null) // 월말 결정이라 사전 계산 생략
                .lastSignal(s.lastSignal)
                .currentWeights(s.currentWeights)
                .recentExecutions(recent)
                .build();
    }

    private QuantExecutionLog logError(MutableState state, QuantStrategyType type,
                                        QuantExecutionLog.ExecutionKind kind,
                                        LocalDateTime now, String msg) {
        log.warn("Quant execution error {} {}: {}", type, kind, msg);
        QuantExecutionLog entry = QuantExecutionLog.builder()
                .executedAt(now).strategyType(type).kind(kind)
                .errorMessage(msg)
                .orders(List.of())
                .build();
        state.appendLog(entry);
        persistExecution(entry);
        return entry;
    }

    private void sendExecutionEmail(QuantStrategyType type, QuantStrategy strat, QuantExecutionLog entry) {
        if (!emailService.isConfigured()) return;
        String subject = String.format("[퀀트 실행] %s %s",
                strat.displayName(),
                entry.getErrorMessage() == null ? "리밸런싱 완료" : "오류");
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:sans-serif'>");
        sb.append("<h3>").append(strat.displayName()).append(" 실행 결과</h3>");
        sb.append("<p>실행 시각: ").append(entry.getExecutedAt()).append("</p>");
        sb.append("<p>종류: ").append(entry.getKind()).append("</p>");
        if (entry.getSignal() != null) {
            sb.append("<p>시그널: ").append(entry.getSignal().getRationale()).append("</p>");
        }
        if (entry.getErrorMessage() != null) {
            sb.append("<p style='color:#B91C1C'>오류: ").append(entry.getErrorMessage()).append("</p>");
        }
        if (entry.getOrders() != null && !entry.getOrders().isEmpty()) {
            sb.append("<table style='border-collapse:collapse'><tr>")
                    .append(th("종목")).append(th("구분")).append(th("수량"))
                    .append(th("가격")).append(th("결과")).append("</tr>");
            for (var o : entry.getOrders()) {
                sb.append("<tr>")
                        .append(td(o.getName() + " (" + o.getSymbol() + ")"))
                        .append(td(o.getSide()))
                        .append(td(String.valueOf(o.getQuantity())))
                        .append(td(o.getPrice() == null ? "-" : o.getPrice().toPlainString()))
                        .append(td((o.isSuccess() ? "✅ " : "❌ ") + o.getMessage()))
                        .append("</tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>");
        emailService.sendHtml(subject, sb.toString());
    }

    private static String th(String s) {
        return "<th style='padding:4px 10px;border-bottom:1px solid #ccc;text-align:left'>" + s + "</th>";
    }
    private static String td(String s) {
        return "<td style='padding:4px 10px;border-bottom:1px solid #eee'>" + s + "</td>";
    }

    // ========== 내부 상태 타입 ==========

    private static class MutableState {
        final QuantStrategyType type;
        boolean enabled = false;
        BigDecimal allocated = BigDecimal.ZERO;
        List<QuantAsset> universe;
        Integer topN, momentumMonths, smaMonths;
        LocalDateTime lastRunAt;
        LocalDate lastRebalanceDate;
        QuantSignal lastSignal;
        Map<String, BigDecimal> currentWeights = new LinkedHashMap<>();
        final Deque<QuantExecutionLog> recent = new ArrayDeque<>();

        MutableState(QuantStrategyType type) { this.type = type; }

        void appendLog(QuantExecutionLog e) {
            recent.addFirst(e);
            while (recent.size() > MAX_EXECUTION_LOG_PER_STRATEGY) recent.removeLast();
        }
    }
}

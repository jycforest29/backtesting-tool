package com.backtesting.service;

import com.backtesting.events.EventPublisher;
import com.backtesting.events.TradeEvent;
import com.backtesting.model.*;
import com.backtesting.persistence.OcoPositionEntity;
import com.backtesting.persistence.OcoPositionRepository;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.KisTradingService;
import com.backtesting.service.kis.KisWebSocketClient;
import com.backtesting.service.kis.MarketSymbol;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 자동매매 엔진 (국내 단타 전용).
 *
 * 3가지 진입 모드:
 *  - LIMIT / MARKET : 즉시 매수
 *  - BREAKOUT_ABOVE : 현재가 ≥ triggerPrice 되면 시장가 매수 (돌파 추종)
 *  - BREAKOUT_BELOW : 현재가 ≤ triggerPrice 되면 triggerPrice 지정가 매수 (하락 포집)
 *
 * 조건부 매수는 예수금 부족 시 실행 보류 → 자금 회복되면 자동 재시도 (무한).
 * 한 포지션당 본주문 1회만 성공 → 이후 손절/익절 플로우로 진입, CLOSED 시 종료.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcoOrderService {

    private final KisTradingService kisTrading;
    private final KisWebSocketClient kisWs;
    private final KisMarketDataService marketData;
    private final EmailService emailService;
    private final DailyLossGuard lossGuard;
    private final OcoPositionRepository repo;
    private final EventPublisher events;

    /** key = position.id. 메모리는 DB 복원본 + 신규. */
    private final Map<String, OcoPosition> positions = new ConcurrentHashMap<>();
    /** 종목별 활성 참조 수 — 0이 되면 WS 구독 해지 */
    private final Map<String, Integer> symbolRefCount = new ConcurrentHashMap<>();
    /** 포지션별 마지막 매수 시도 시각 — rate limit 보호용 cooldown */
    private final Map<String, LocalDateTime> lastBuyAttempt = new ConcurrentHashMap<>();

    /**
     * DB 복구 완료 전 WS tick 은 무시. 복구 중 onTick 이 positions 을 조작하면
     * stale 상태로 의사결정 위험.
     */
    private final AtomicBoolean recoveryComplete = new AtomicBoolean(false);

    /** removeTickListener 에 전달할 동일 참조 보관 — identity 일치 필수. */
    private BiConsumer<String, BigDecimal> tickListenerRef;

    /** 셧다운 동안 신규 주문 / 리밸런스를 차단. */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private static final Duration BUY_ATTEMPT_COOLDOWN = Duration.ofSeconds(10);

    // 기본 익절 단계
    private static final List<double[]> DEFAULT_TP = List.of(
            new double[]{2.0, 0.33},
            new double[]{4.0, 0.33},
            new double[]{6.0, 0.34}
    );

    /** 종결 상태 — 기동 복구 시 건너뜀. */
    private static final List<OcoPosition.OcoStatus> TERMINAL = List.of(
            OcoPosition.OcoStatus.CLOSED,
            OcoPosition.OcoStatus.CANCELLED,
            OcoPosition.OcoStatus.FAILED
    );
    /** 복구 대상 상태 — 기동 시 WS 재구독. */
    private static final List<OcoPosition.OcoStatus> RECOVERABLE = List.of(
            OcoPosition.OcoStatus.PENDING_ENTRY,
            OcoPosition.OcoStatus.PENDING_FILL,
            OcoPosition.OcoStatus.ACTIVE,
            OcoPosition.OcoStatus.PARTIALLY_CLOSED
    );

    @PostConstruct
    void wireTickListener() {
        // DB 복구가 끝나기 전까지는 tick 을 무시 — 순서 보장.
        this.tickListenerRef = this::onTickGuarded;
        kisWs.addTickListener(tickListenerRef);
        try {
            recoverFromDb();
        } finally {
            recoveryComplete.set(true);
        }
    }

    @PreDestroy
    void unwireTickListener() {
        shuttingDown.set(true);
        if (tickListenerRef != null) {
            kisWs.removeTickListener(tickListenerRef);
        }
        log.info("OcoOrderService shutdown: tick listener detached, positionsInMemory={}", positions.size());
    }

    /**
     * recoveryComplete 전 또는 shutdown 중에는 드롭. 그 외엔 기존 onTick 로 위임.
     * listener identity 를 유지하기 위한 wrapper (removeTickListener 가 동일 참조를 요구).
     */
    void onTickGuarded(String code, BigDecimal price) {
        if (shuttingDown.get()) return;
        if (!recoveryComplete.get()) return;
        onTick(code, price);
    }

    /**
     * 기동 복구.
     * 1. DB에서 살아있는 포지션 로드
     * 2. ACTIVE/PARTIALLY_CLOSED 포지션은 KIS 잔고와 대조 — 외부에서 이미 매도되었다면 CLOSED 처리
     * 3. 남은 포지션은 WS 구독 복원
     * 4. PENDING_FILL은 잔고 재조회로 체결 여부 확인
     */
    private void recoverFromDb() {
        List<OcoPositionEntity> alive;
        try {
            alive = repo.findByStatusIn(RECOVERABLE);
        } catch (Exception e) {
            log.error("OCO DB recovery failed: {}", e.getMessage(), e);
            return;
        }
        if (alive.isEmpty()) {
            log.info("OCO recovery: no alive positions");
            return;
        }

        // KIS 잔고 1회 조회 — 모든 포지션의 기준
        BalanceResult balance;
        Map<String, Long> heldQty = new HashMap<>();
        try {
            balance = kisTrading.getBalance(AssetType.KR_STOCK);
            for (var h : balance.getHoldings()) heldQty.put(h.getSymbol(), h.getQuantity());
        } catch (Exception e) {
            log.warn("OCO recovery: balance fetch failed — proceeding without external-close check: {}", e.getMessage());
        }

        int restored = 0, externallyClosed = 0;
        for (OcoPositionEntity e : alive) {
            OcoPosition p = e.toDomain();

            // ACTIVE/PARTIALLY_CLOSED인데 KIS에 수량이 부족하다 = 외부에서 매도됨
            if (!heldQty.isEmpty()
                    && (p.getStatus() == OcoPosition.OcoStatus.ACTIVE
                        || p.getStatus() == OcoPosition.OcoStatus.PARTIALLY_CLOSED)) {
                long have = heldQty.getOrDefault(p.getSymbol(), 0L);
                if (have < p.getRemainingQuantity()) {
                    p.setStatus(OcoPosition.OcoStatus.CLOSED);
                    p.setCloseReason("EXTERNAL_CLOSE_DETECTED");
                    p.setClosedAt(LocalDateTime.now());
                    repo.save(OcoPositionEntity.fromDomain(p));
                    externallyClosed++;
                    continue;
                }
            }
            positions.put(p.getId(), p);
            acquireSubscription(p.getSymbol());
            restored++;
        }
        log.info("OCO recovery: restored={} externalClose={}", restored, externallyClosed);
    }

    // ========== 등록 / 조회 / 취소 ==========

    public OcoPosition register(OcoRequest req) {
        validate(req);
        // 이미 보유 중 모드는 매수 안 하므로 손실가드 체크 불필요.
        if (!isExistingHolding(req.getEntryType()) && !lossGuard.isBuyAllowed()) {
            throw new IllegalStateException(String.format(
                    "일일 손실 한도 초과 — 오늘 누적 %+,d원, 한도 -%,d원. 자정 리셋.",
                    lossGuard.getNetRealizedKrw(), lossGuard.getLimitKrw()));
        }

        OcoPosition pos = buildPosition(req);

        if (isExistingHolding(req.getEntryType())) {
            // 보유 주식 커버리지 확인 + 기준가 결정. 실패 시 position은 저장하지 않음.
            BigDecimal refPrice = prepareExistingHolding(req, pos);
            positions.put(pos.getId(), pos);
            persist(pos);                        // 먼저 PENDING 저장 → activate로 ACTIVE 갱신
            activate(pos, refPrice);
            log.info("Existing-holding OCO armed id={} symbol={} qty={} refPrice={}",
                    pos.getId(), pos.getSymbol(), pos.getTotalQuantity(), refPrice);
            return pos;
        }

        positions.put(pos.getId(), pos);

        if (isConditional(req.getEntryType())) {
            // 조건부 매수: 본주문은 아직 안 냄. WS 구독만 시작.
            persist(pos);
            acquireSubscription(pos.getSymbol());
            log.info("Conditional entry armed id={} symbol={} type={} trigger={}",
                    pos.getId(), pos.getSymbol(), req.getEntryType(), pos.getTriggerPrice());
        } else {
            // 즉시 매수: 주문 전 '주문 시도 중' 상태를 먼저 DB 기록 →
            // 만약 서버가 여기서 크래시해도 복구 시 PENDING_FILL 상태로 KIS 체결 확인 가능.
            persist(pos);
            OrderResult mainResult;
            try {
                mainResult = placeMainBuy(pos);
            } catch (Exception placeEx) {
                // 네트워크 타임아웃 등 — KIS에 주문이 접수됐는지 불확실.
                // reconcileUnknownOrder로 미체결 조회해서 실제 도달 여부 확인.
                log.warn("Main order placement threw; reconciling. err={}", placeEx.getMessage());
                mainResult = reconcileUnknownOrder(pos, placeEx.getMessage());
            }
            if (!mainResult.isSuccess()) {
                pos.setStatus(OcoPosition.OcoStatus.FAILED);
                pos.setLastBuyFailReason(mainResult.getMessage());
                persist(pos);
                throw new IllegalStateException("Main order failed: " + mainResult.getMessage());
            }
            pos.setMainOrderNo(mainResult.getOrderNo());
            pos.setStatus(OcoPosition.OcoStatus.PENDING_FILL);
            persist(pos);
            log.info("Immediate entry id={} symbol={} qty={} orderNo={}",
                    pos.getId(), pos.getSymbol(), pos.getTotalQuantity(), pos.getMainOrderNo());
        }
        return pos;
    }

    /**
     * 주문 전송이 예외로 끝났을 때 — 진짜 실패인지, 네트워크 끊김 중 실제로 접수됐는지 확인.
     * KIS 미체결 조회로 symbol+side+qty 매칭 주문이 있으면 성공 처리.
     * 중복 주문 방지 핵심 로직.
     */
    private OrderResult reconcileUnknownOrder(OcoPosition pos, String originalError) {
        try {
            List<Map<String, Object>> open = kisTrading.getOpenOrders();
            for (Map<String, Object> o : open) {
                String sym = String.valueOf(o.get("symbol"));
                String side = String.valueOf(o.get("side"));
                long qty = o.get("quantity") instanceof Number n ? n.longValue() : 0;
                // side: 02=매수, 01=매도
                boolean matchBuy = "02".equals(side);
                if (matchBuy && sym.equals(pos.getSymbol()) && qty == pos.getTotalQuantity()) {
                    String orderNo = String.valueOf(o.get("orderNo"));
                    log.warn("Reconciled unknown-state order via open-orders match: pos={} kisOrderNo={}",
                            pos.getId(), orderNo);
                    return OrderResult.builder()
                            .success(true).orderNo(orderNo).rawCode("RECONCILED")
                            .message("주문 접수 확인 (네트워크 장애 후 재대조)").build();
                }
            }
        } catch (Exception e) {
            log.warn("Open-orders reconcile failed: {}", e.getMessage());
        }
        return OrderResult.builder().success(false)
                .message("주문 실패 (네트워크 오류, 미체결 조회에서도 매칭 없음): " + originalError).build();
    }

    /** 단일 진입점으로 DB 저장 통일. repo 예외는 로깅만 (주문 흐름 막지 않음). */
    private void persist(OcoPosition p) {
        try {
            repo.save(OcoPositionEntity.fromDomain(p));
        } catch (Exception e) {
            log.error("OCO persist failed id={}: {}", p.getId(), e.getMessage());
        }
    }

    /**
     * 보유 주식 기반 OCO 준비.
     * - 잔고에 해당 종목이 있고, 요청 수량 이상 보유 중인지
     * - 기존 활성 OCO들이 이 종목에 걸어놓은 수량과 합쳐서 보유량 초과하지 않는지
     * - 손절/익절 기준가 반환 (요청 entryPrice > KIS 평단 순)
     */
    private BigDecimal prepareExistingHolding(OcoRequest req, OcoPosition pos) {
        BalanceResult balance;
        try {
            balance = kisTrading.getBalance(AssetType.KR_STOCK);
        } catch (Exception e) {
            throw new IllegalStateException("잔고 조회 실패: " + e.getMessage());
        }
        BalanceResult.Holding h = balance.getHoldings().stream()
                .filter(x -> x.getSymbol().equals(pos.getSymbol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "보유 중이 아닙니다: " + pos.getSymbol()));
        if (h.getQuantity() < pos.getTotalQuantity()) {
            throw new IllegalStateException(String.format(
                    "보유 수량(%d주)이 요청 수량(%d주)보다 적습니다",
                    h.getQuantity(), pos.getTotalQuantity()));
        }

        // 이미 활성 상태인 OCO들이 이 종목에 걸어놓은 수량 합계
        long alreadyCovered = positions.values().stream()
                .filter(p -> p.getSymbol().equals(pos.getSymbol()))
                .filter(p -> p.getStatus() == OcoPosition.OcoStatus.ACTIVE
                        || p.getStatus() == OcoPosition.OcoStatus.PARTIALLY_CLOSED
                        || p.getStatus() == OcoPosition.OcoStatus.PENDING_FILL)
                .mapToLong(OcoPosition::getRemainingQuantity)
                .sum();
        if (alreadyCovered + pos.getTotalQuantity() > h.getQuantity()) {
            throw new IllegalStateException(String.format(
                    "이미 %d주에 OCO가 걸려있음. 보유 %d주 중 %d주만 추가 가능",
                    alreadyCovered, h.getQuantity(), h.getQuantity() - alreadyCovered));
        }

        // 기준가: 사용자 지정 > 평단가
        BigDecimal refPrice;
        if (req.getEntryPrice() != null && !req.getEntryPrice().isBlank()) {
            try {
                refPrice = new BigDecimal(req.getEntryPrice());
                if (refPrice.signum() <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("entryPrice(기준가)는 양수여야 합니다: " + req.getEntryPrice());
            }
        } else {
            refPrice = h.getAvgPrice();
            if (refPrice == null || refPrice.signum() <= 0) {
                throw new IllegalStateException("잔고 평단가가 없습니다. 기준가를 직접 입력하세요.");
            }
        }
        return refPrice;
    }

    private boolean isExistingHolding(String type) {
        return "EXISTING_HOLDING".equalsIgnoreCase(type);
    }

    public Collection<OcoPosition> list() { return positions.values(); }
    public OcoPosition get(String id)     { return positions.get(id); }

    public OcoPosition cancel(String id) {
        OcoPosition pos = positions.get(id);
        if (pos == null) throw new IllegalArgumentException("position not found: " + id);
        pos.setStatus(OcoPosition.OcoStatus.CANCELLED);
        pos.setClosedAt(LocalDateTime.now());
        pos.setCloseReason("MANUAL_CANCEL");
        persist(pos);
        releaseSubscription(pos.getSymbol());
        return pos;
    }

    private OcoPosition buildPosition(OcoRequest req) {
        List<double[]> tpSpec = (req.getTakeProfit() == null || req.getTakeProfit().isEmpty())
                ? DEFAULT_TP
                : req.getTakeProfit().stream()
                    .map(l -> new double[]{l.getPercent(), l.getQuantityFraction()})
                    .toList();

        List<OcoPosition.TakeProfitLegState> legs = new ArrayList<>();
        long remaining = req.getQuantity();
        for (int i = 0; i < tpSpec.size(); i++) {
            double[] s = tpSpec.get(i);
            long qty = (i == tpSpec.size() - 1) ? remaining : Math.round(req.getQuantity() * s[1]);
            qty = Math.min(qty, remaining);
            remaining -= qty;
            legs.add(OcoPosition.TakeProfitLegState.builder()
                    .percent(s[0]).quantityFraction(s[1])
                    .plannedQuantity(qty).executedQuantity(0L)
                    .triggered(false)
                    .build());
        }

        BigDecimal trigger = req.getTriggerPrice() == null || req.getTriggerPrice().isBlank()
                ? null : new BigDecimal(req.getTriggerPrice());

        return OcoPosition.builder()
                .id(UUID.randomUUID().toString())
                .symbol(req.getSymbol())
                .totalQuantity(req.getQuantity())
                .remainingQuantity(req.getQuantity())
                .stopLossPercent(req.getStopLossPercent())
                .takeProfit(legs)
                .entryType(req.getEntryType())
                .triggerPrice(trigger)
                .entryPriceRequested(req.getEntryPrice())
                .buyAttempts(0)
                .status(isExistingHolding(req.getEntryType())
                        ? OcoPosition.OcoStatus.ACTIVE              // register()에서 activate() 호출로 세팅 — 초기값은 표시만
                        : isConditional(req.getEntryType())
                            ? OcoPosition.OcoStatus.PENDING_ENTRY
                            : OcoPosition.OcoStatus.PENDING_FILL)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private OrderResult placeMainBuy(OcoPosition pos) {
        OrderRequest main = new OrderRequest();
        main.setMarket(AssetType.KR_STOCK);
        main.setSymbol(pos.getSymbol());
        main.setSide("BUY");
        String type = pos.getEntryType();
        if ("BREAKOUT_ABOVE".equalsIgnoreCase(type)) {
            main.setOrderType("MARKET");
        } else if ("BREAKOUT_BELOW".equalsIgnoreCase(type)) {
            main.setOrderType("LIMIT");
            main.setPrice(pos.getTriggerPrice().toPlainString());
        } else {
            main.setOrderType(type);
            main.setPrice(pos.getEntryPriceRequested());
        }
        main.setQuantity(pos.getTotalQuantity());
        return kisTrading.placeOrder(main);
    }

    // ========== 조건 매수 트리거 ==========

    private boolean isConditional(String type) {
        return "BREAKOUT_ABOVE".equalsIgnoreCase(type) || "BREAKOUT_BELOW".equalsIgnoreCase(type);
    }

    private boolean conditionMet(OcoPosition pos, BigDecimal price) {
        if (pos.getTriggerPrice() == null) return false;
        if ("BREAKOUT_ABOVE".equalsIgnoreCase(pos.getEntryType())) {
            return price.compareTo(pos.getTriggerPrice()) >= 0;
        }
        if ("BREAKOUT_BELOW".equalsIgnoreCase(pos.getEntryType())) {
            return price.compareTo(pos.getTriggerPrice()) <= 0;
        }
        return false;
    }

    /** 예수금으로 본주문 매수 가능한지 체크. 필요 금액에 버퍼 0.5% 붙여 수수료/세금 고려. */
    private boolean hasSufficientFunds(OcoPosition pos, BigDecimal refPrice) {
        try {
            BalanceResult balance = kisTrading.getBalance(AssetType.KR_STOCK);
            BigDecimal required = refPrice.multiply(BigDecimal.valueOf(pos.getTotalQuantity()))
                    .multiply(BigDecimal.valueOf(1.005));
            boolean ok = balance.getDeposit() != null
                    && balance.getDeposit().compareTo(required) >= 0;
            if (!ok) {
                pos.setLastBuyFailReason(String.format(
                        "예수금 부족: 필요 %s원 / 예수금 %s원",
                        required.setScale(0, RoundingMode.HALF_UP),
                        balance.getDeposit() == null ? "0" : balance.getDeposit().setScale(0, RoundingMode.HALF_UP)));
            }
            return ok;
        } catch (Exception e) {
            pos.setLastBuyFailReason("잔고 조회 실패: " + e.getMessage());
            log.warn("funds check failed {}: {}", pos.getId(), e.getMessage());
            return false;
        }
    }

    private void attemptConditionalBuy(OcoPosition pos, BigDecimal price) {
        // cooldown: 직전 시도 후 짧은 시간 내 재시도 방지 (잔고 API rate 부담)
        LocalDateTime last = lastBuyAttempt.get(pos.getId());
        if (last != null && Duration.between(last, LocalDateTime.now()).compareTo(BUY_ATTEMPT_COOLDOWN) < 0) {
            return;
        }
        lastBuyAttempt.put(pos.getId(), LocalDateTime.now());

        // 일일 손실 한도 초과 시 조건 만족해도 매수 보류
        if (!lossGuard.isBuyAllowed()) {
            pos.setLastBuyFailReason(String.format(
                    "일일 손실 한도 초과 (누적 %+,d원) — 자정 리셋 후 재시도",
                    lossGuard.getNetRealizedKrw()));
            pos.setBuyAttempts(pos.getBuyAttempts() + 1);
            return;
        }

        BigDecimal refPrice = "BREAKOUT_BELOW".equalsIgnoreCase(pos.getEntryType())
                ? pos.getTriggerPrice()
                : price;

        if (!hasSufficientFunds(pos, refPrice)) {
            pos.setBuyAttempts(pos.getBuyAttempts() + 1);
            if (pos.getBuyAttempts() == 1 || pos.getBuyAttempts() % 10 == 0) {
                log.info("Buy deferred id={} reason={}", pos.getId(), pos.getLastBuyFailReason());
            }
            return;
        }

        OrderResult res;
        try {
            res = placeMainBuy(pos);
        } catch (Exception e) {
            res = reconcileUnknownOrder(pos, e.getMessage());
        }
        if (!res.isSuccess()) {
            pos.setLastBuyFailReason(res.getMessage());
            pos.setBuyAttempts(pos.getBuyAttempts() + 1);
            persist(pos);
            log.warn("Conditional buy placement failed id={}: {}", pos.getId(), res.getMessage());
            return;
        }
        pos.setMainOrderNo(res.getOrderNo());
        pos.setStatus(OcoPosition.OcoStatus.PENDING_FILL);
        persist(pos);
        log.info("Conditional buy fired id={} symbol={} @ {} orderNo={}",
                pos.getId(), pos.getSymbol(), price, res.getOrderNo());
        safeEmail("[매수 발동] " + pos.getSymbol() + " " + pos.getEntryType(),
                renderEntryFiredMail(pos, price, res));
    }

    // ========== 체결 감시 (PENDING_FILL → ACTIVE) ==========

    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public void checkPendingFills() {
        var pending = positions.values().stream()
                .filter(p -> p.getStatus() == OcoPosition.OcoStatus.PENDING_FILL)
                .toList();
        if (pending.isEmpty()) return;

        try {
            BalanceResult balance = kisTrading.getBalance(AssetType.KR_STOCK);
            for (OcoPosition pos : pending) {
                balance.getHoldings().stream()
                        .filter(h -> h.getSymbol().equals(pos.getSymbol())
                                && h.getQuantity() >= pos.getTotalQuantity())
                        .findFirst()
                        .ifPresent(h -> activate(pos, h.getAvgPrice()));
            }
        } catch (Exception e) {
            log.warn("pending fill check failed: {}", e.getMessage());
        }
    }

    /**
     * WS 끊김 백업 — 60초마다 ACTIVE/PENDING_ENTRY 포지션 현재가 폴링.
     * WebSocket이 정상 동작해서 onTick()으로 이미 트리거된 경우엔 중복 실행 방지됨
     * (executeStopLoss가 remainingQuantity=0으로 바꾸기 때문).
     * KIS rate limit 부담이 있으므로 활성 종목만, 고유 종목 기준 1회씩.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void pollActivePositions() {
        var relevant = positions.values().stream()
                .filter(p -> p.getStatus() == OcoPosition.OcoStatus.ACTIVE
                        || p.getStatus() == OcoPosition.OcoStatus.PARTIALLY_CLOSED
                        || p.getStatus() == OcoPosition.OcoStatus.PENDING_ENTRY)
                .toList();
        if (relevant.isEmpty()) return;

        // 고유 심볼 집합 — 같은 종목에 여러 포지션 걸려있어도 시세 1회만 조회
        Set<String> symbols = new LinkedHashSet<>();
        for (var p : relevant) symbols.add(p.getSymbol());

        for (String sym : symbols) {
            try {
                BigDecimal price = marketData.getQuote(
                        MarketSymbol.of(AssetType.KR_STOCK, sym)).price();
                if (price == null || price.signum() == 0) continue;
                // 해당 심볼에 걸린 모든 포지션에 가격 이벤트 재생 — WS 미수신 보정
                onTick(sym, price);
            } catch (Exception e) {
                log.debug("Poll fallback fetch failed {}: {}", sym, e.getMessage());
            }
        }
    }

    private void activate(OcoPosition pos, BigDecimal avgPrice) {
        pos.setEntryPrice(avgPrice);
        BigDecimal stopPx = avgPrice.multiply(BigDecimal.valueOf(1 + pos.getStopLossPercent() / 100.0))
                .setScale(0, RoundingMode.HALF_UP);
        pos.setStopLossPrice(stopPx);
        for (var leg : pos.getTakeProfit()) {
            BigDecimal triggerPx = avgPrice.multiply(BigDecimal.valueOf(1 + leg.getPercent() / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);
            leg.setTriggerPrice(triggerPx);
        }
        pos.setStatus(OcoPosition.OcoStatus.ACTIVE);
        pos.setActivatedAt(LocalDateTime.now());
        persist(pos);
        acquireSubscription(pos.getSymbol()); // 조건부의 경우 이미 구독돼 있으므로 refCount만 증가
        log.info("OCO activated id={} entry={} stop={} tp={}",
                pos.getId(), avgPrice, stopPx,
                pos.getTakeProfit().stream().map(OcoPosition.TakeProfitLegState::getTriggerPrice).toList());
        safeEmail("[감시 시작] " + pos.getSymbol() + " 본주문 체결 확인",
                renderActivationMail(pos));
    }

    /** 메일 장애가 주문 흐름을 막지 않도록 격리. */
    private void safeEmail(String subject, String html) {
        try {
            emailService.sendHtml(subject, html);
        } catch (Exception e) {
            log.warn("Email send failed (non-fatal): {}", e.getMessage());
        }
    }

    // ========== WS 구독 관리 ==========

    private synchronized void acquireSubscription(String code) {
        int count = symbolRefCount.getOrDefault(code, 0) + 1;
        symbolRefCount.put(code, count);
        if (count == 1) kisWs.subscribe(code);
    }

    private synchronized void releaseSubscription(String code) {
        Integer count = symbolRefCount.get(code);
        if (count == null) return;
        count--;
        if (count <= 0) {
            symbolRefCount.remove(code);
            kisWs.unsubscribe(code);
        } else {
            symbolRefCount.put(code, count);
        }
    }

    // ========== 실시간 틱 처리 ==========

    public void onTick(String code, BigDecimal price) {
        var relevant = positions.values().stream()
                .filter(p -> p.getSymbol().equals(code))
                .toList();
        for (OcoPosition pos : relevant) {
            try {
                switch (pos.getStatus()) {
                    case PENDING_ENTRY -> {
                        if (conditionMet(pos, price)) attemptConditionalBuy(pos, price);
                    }
                    case ACTIVE, PARTIALLY_CLOSED -> evaluateExit(pos, price);
                    default -> { /* PENDING_FILL, CLOSED, CANCELLED, FAILED: no tick action */ }
                }
            } catch (Exception e) {
                log.error("OCO tick eval error {}: {}", pos.getId(), e.getMessage(), e);
            }
        }
    }

    private void evaluateExit(OcoPosition pos, BigDecimal price) {
        if (price.compareTo(pos.getStopLossPrice()) <= 0) {
            executeStopLoss(pos, price);
            return;
        }
        for (var leg : pos.getTakeProfit()) {
            if (leg.isTriggered()) continue;
            if (price.compareTo(leg.getTriggerPrice()) >= 0) {
                executeTakeProfit(pos, leg, price);
            } else {
                break;
            }
        }
    }

    private void executeStopLoss(OcoPosition pos, BigDecimal price) {
        long qty = pos.getRemainingQuantity();
        if (qty <= 0) return;
        log.warn("OCO STOP_LOSS id={} symbol={} price={} qty={}", pos.getId(), pos.getSymbol(), price, qty);
        OrderResult res;
        try {
            res = placeMarketSell(pos.getSymbol(), qty);
        } catch (Exception e) {
            log.error("Stop-loss sell threw — retrying once", e);
            res = placeMarketSell(pos.getSymbol(), qty); // 매도는 중복 리스크 낮음, 1회 재시도 허용
        }
        events.publishTrade(TradeEvent.builder()
                .eventType("STOP_LOSS").occurredAt(LocalDateTime.now())
                .source("OcoOrderService").symbol(pos.getSymbol())
                .side("SELL").quantity(qty).price(price)
                .positionId(pos.getId()).orderNo(res.getOrderNo())
                .success(res.isSuccess()).message(res.getMessage())
                .build());
        long pnl = price.subtract(pos.getEntryPrice())
                .multiply(BigDecimal.valueOf(qty)).longValue();
        lossGuard.recordRealizedPnl(pnl);
        pos.setRemainingQuantity(0);
        pos.setStatus(OcoPosition.OcoStatus.CLOSED);
        pos.setCloseReason("STOP_LOSS");
        pos.setClosedAt(LocalDateTime.now());
        persist(pos);
        releaseSubscription(pos.getSymbol());
        safeEmail("[손절 체결] " + pos.getSymbol() + " @ " + price,
                renderExitMail(pos, "손절", price, qty, res));
    }

    private void executeTakeProfit(OcoPosition pos, OcoPosition.TakeProfitLegState leg, BigDecimal price) {
        long qty = Math.min(leg.getPlannedQuantity(), pos.getRemainingQuantity());
        if (qty <= 0) { leg.setTriggered(true); persist(pos); return; }
        log.info("OCO TP id={} leg=+{}% price={} qty={}", pos.getId(), leg.getPercent(), price, qty);
        OrderResult res = placeMarketSell(pos.getSymbol(), qty);
        events.publishTrade(TradeEvent.builder()
                .eventType("TAKE_PROFIT").occurredAt(LocalDateTime.now())
                .source("OcoOrderService").symbol(pos.getSymbol())
                .side("SELL").quantity(qty).price(price)
                .positionId(pos.getId()).orderNo(res.getOrderNo())
                .success(res.isSuccess()).message("TP +" + leg.getPercent() + "%")
                .build());
        long pnl = price.subtract(pos.getEntryPrice())
                .multiply(BigDecimal.valueOf(qty)).longValue();
        lossGuard.recordRealizedPnl(pnl);
        leg.setTriggered(true);
        leg.setExecutedQuantity(qty);
        leg.setOrderNo(res.getOrderNo());
        pos.setRemainingQuantity(pos.getRemainingQuantity() - qty);

        if (pos.getRemainingQuantity() <= 0) {
            pos.setStatus(OcoPosition.OcoStatus.CLOSED);
            pos.setCloseReason("TAKE_PROFIT_ALL");
            pos.setClosedAt(LocalDateTime.now());
            releaseSubscription(pos.getSymbol());
        } else {
            pos.setStatus(OcoPosition.OcoStatus.PARTIALLY_CLOSED);
        }
        persist(pos);
        safeEmail(String.format("[익절 체결] %s +%.1f%% @ %s", pos.getSymbol(), leg.getPercent(), price),
                renderExitMail(pos, String.format("익절 +%.1f%%", leg.getPercent()), price, qty, res));
    }

    private OrderResult placeMarketSell(String symbol, long qty) {
        OrderRequest req = new OrderRequest();
        req.setMarket(AssetType.KR_STOCK);
        req.setSymbol(symbol);
        req.setSide("SELL");
        req.setOrderType("MARKET");
        req.setQuantity(qty);
        return kisTrading.placeOrder(req);
    }

    // ========== 검증 ==========

    private void validate(OcoRequest req) {
        if (req.getSymbol() == null || req.getSymbol().isBlank())
            throw new IllegalArgumentException("symbol required");
        if (req.getQuantity() <= 0)
            throw new IllegalArgumentException("quantity must be positive");
        if (req.getEntryType() == null
                || !req.getEntryType().matches("(?i)LIMIT|MARKET|BREAKOUT_ABOVE|BREAKOUT_BELOW|EXISTING_HOLDING"))
            throw new IllegalArgumentException(
                    "entryType must be LIMIT / MARKET / BREAKOUT_ABOVE / BREAKOUT_BELOW / EXISTING_HOLDING");
        if ("LIMIT".equalsIgnoreCase(req.getEntryType())
                && (req.getEntryPrice() == null || req.getEntryPrice().isBlank()))
            throw new IllegalArgumentException("entryPrice required for LIMIT");
        if (isConditional(req.getEntryType())
                && (req.getTriggerPrice() == null || req.getTriggerPrice().isBlank()))
            throw new IllegalArgumentException("triggerPrice required for conditional entry");
        if (req.getStopLossPercent() >= 0)
            throw new IllegalArgumentException("stopLossPercent must be negative (e.g. -2.0)");
        if (req.getTakeProfit() != null) {
            double sum = req.getTakeProfit().stream()
                    .mapToDouble(OcoRequest.TakeProfitLeg::getQuantityFraction).sum();
            if (sum > 1.0001 || sum < 0.9999)
                throw new IllegalArgumentException("takeProfit fractions must sum to 1.0 (current=" + sum + ")");
            for (var l : req.getTakeProfit()) {
                if (l.getPercent() <= 0)
                    throw new IllegalArgumentException("takeProfit percent must be positive");
            }
        }
    }

    // ========== 메일 렌더 ==========

    private String renderEntryFiredMail(OcoPosition pos, BigDecimal triggerPrice, OrderResult res) {
        return "<div style='font-family:sans-serif'>"
                + "<h3>조건부 매수 발동</h3>"
                + "<p>종목 " + pos.getSymbol() + " · 수량 " + pos.getTotalQuantity() + "주</p>"
                + "<p>감시 조건: " + pos.getEntryType() + " @ " + pos.getTriggerPrice() + "</p>"
                + "<p>발동 시 현재가: <b>" + triggerPrice + "</b></p>"
                + "<p>매수 주문 결과: " + (res.isSuccess() ? "접수 OK · " + res.getOrderNo() : "실패 - " + res.getMessage()) + "</p>"
                + "<p style='color:#999;font-size:12px'>체결 확인 후 손절/익절 감시 자동 시작.</p>"
                + "</div>";
    }

    private String renderActivationMail(OcoPosition pos) {
        StringBuilder sb = new StringBuilder("<div style='font-family:sans-serif'>");
        sb.append("<h3>자동 손절/익절 감시 시작</h3>");
        sb.append("<p>종목 ").append(pos.getSymbol()).append(" · 수량 ")
                .append(pos.getTotalQuantity()).append("주 · 평단 ").append(pos.getEntryPrice()).append("</p>");
        sb.append("<p>손절가: <b>").append(pos.getStopLossPrice()).append("</b> (")
                .append(pos.getStopLossPercent()).append("%)</p>");
        sb.append("<p>익절 단계:</p><ul>");
        for (var leg : pos.getTakeProfit()) {
            sb.append("<li>+").append(leg.getPercent()).append("% @ ")
                    .append(leg.getTriggerPrice()).append(" (").append(leg.getPlannedQuantity()).append("주)</li>");
        }
        sb.append("</ul></div>");
        return sb.toString();
    }

    private String renderExitMail(OcoPosition pos, String kind, BigDecimal price, long qty, OrderResult res) {
        return "<div style='font-family:sans-serif'>"
                + "<h3>" + kind + " 실행</h3>"
                + "<p>종목 " + pos.getSymbol() + "</p>"
                + "<p>체결가(참고) <b>" + price + "</b> · 수량 " + qty + "주</p>"
                + "<p>주문 결과: " + (res.isSuccess() ? "접수 OK · 주문번호 " + res.getOrderNo() : "실패 - " + res.getMessage()) + "</p>"
                + "<p>잔여 수량: " + pos.getRemainingQuantity() + "주 / 총 " + pos.getTotalQuantity() + "주</p>"
                + "</div>";
    }
}

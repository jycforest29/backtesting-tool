package com.backtesting.controller;

import com.backtesting.common.error.LossGuardBlockedException;
import com.backtesting.config.KisProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.BalanceResult;
import com.backtesting.model.LivePrice;
import com.backtesting.model.OcoPosition;
import com.backtesting.model.OcoRequest;
import com.backtesting.model.OrderRequest;
import com.backtesting.model.OrderResult;
import com.backtesting.model.WatchlistRequest;
import com.backtesting.service.AutoTradingService;
import com.backtesting.service.DailyLossGuard;
import com.backtesting.service.DailySupplyDemandReportJob;
import com.backtesting.service.DartDisclosureService;
import com.backtesting.service.EmailService;
import com.backtesting.service.IdempotencyService;
import com.backtesting.service.OcoOrderService;
import com.backtesting.service.OrderExecutionService;
import com.backtesting.service.VolumeSpikeScannerService;
import com.backtesting.service.WeeklyReportJob;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.KisTradingService;
import com.backtesting.service.kis.MarketSymbol;
import com.backtesting.service.metrics.TradingMetrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 자동매매 / 주문 / OCO / 감시종목 / 잔고 / 수동 스케줄 트리거.
 *
 * 에러 처리: controller 내 try/catch 금지. GlobalExceptionHandler 가 ErrorResponse 변환.
 * 일일 손실 한도 초과는 LossGuardBlockedException → 423 LOCKED.
 * Idempotency Conflict/InFlight 는 IdempotencyService 가 던지며 핸들러가 409 로 변환.
 */
@Slf4j
@RestController
@RequestMapping("/api/trading")
@Validated
@RequiredArgsConstructor
public class AutoTradingController {

    private final AutoTradingService autoTrading;
    private final KisTradingService kisTrading;
    private final KisProperties kisProps;
    private final DailySupplyDemandReportJob reportJob;
    private final EmailService emailService;
    private final KisMarketDataService kisMarketData;
    private final OcoOrderService ocoService;
    private final DailyLossGuard lossGuard;
    private final WeeklyReportJob weeklyReport;
    private final DartDisclosureService dartService;
    private final VolumeSpikeScannerService volumeScanner;
    private final OrderExecutionService orderExecution;
    private final IdempotencyService idempotency;
    private final TradingMetrics tradingMetrics;

    // ========== 상태 ==========

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "configured", kisProps.isConfigured(),
                "paperTrading", kisProps.isPaperTrading(),
                "accountConfigured", kisProps.getAccountNumber() != null && !kisProps.getAccountNumber().isBlank()
        );
    }

    // ========== 감시종목 (실시간 가격) ==========

    @PostMapping("/watchlist")
    public Map<String, Object> addWatch(@Valid @RequestBody WatchlistRequest req) {
        autoTrading.subscribe(req.getMarket(), req.getCode(), req.getExchange());
        return Map.of("watchlist", symbols(autoTrading.getWatchlist()));
    }

    @DeleteMapping("/watchlist")
    public Map<String, Object> removeWatch(
            @RequestParam @NotNull @Pattern(regexp = "^(KR_STOCK|US_STOCK|JP_STOCK|CRYPTO)$") String market,
            @RequestParam @NotBlank @Size(max = 20)
            @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,20}$") String code) {
        autoTrading.unsubscribe(AssetType.valueOf(market), code);
        return Map.of("watchlist", symbols(autoTrading.getWatchlist()));
    }

    @GetMapping("/quotes")
    public List<LivePrice> getQuotes() {
        return autoTrading.getQuotes();
    }

    @PostMapping("/refresh")
    public Map<String, String> refresh() {
        autoTrading.refreshAll();
        return Map.of("message", "refresh triggered");
    }

    // ========== 주문 ==========

    /**
     * 주문 접수. side=BUY + 국내 + 손실한도 초과면 LossGuardBlockedException 즉시 throw.
     * X-Idempotency-Key 헤더가 있으면 IdempotencyService 가 중복 제거.
     */
    @PostMapping("/order")
    public ResponseEntity<OrderResult> placeOrder(
            @Valid @RequestBody OrderRequest req,
            @RequestHeader(value = "X-Idempotency-Key", required = false)
            @Size(max = 80) @Pattern(regexp = "^$|^[A-Za-z0-9_\\-.]{8,80}$") String idempotencyKey,
            @AuthenticationPrincipal Object principal) {

        if ("BUY".equalsIgnoreCase(req.getSide())
                && req.getMarket() == AssetType.KR_STOCK
                && !lossGuard.isBuyAllowed()) {
            tradingMetrics.onLossGuardBlocked();
            throw new LossGuardBlockedException(lossGuard.getNetRealizedKrw(), lossGuard.getLimitKrw());
        }

        log.info("order request principal={} market={} symbol={} side={} qty={} idemKey={}",
                principal, req.getMarket(), req.getSymbol(), req.getSide(), req.getQuantity(),
                idempotencyKey == null ? "-" : "set");

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyService.Result<OrderResult> r = idempotency.execute(
                    idempotencyKey, req, OrderResult.class,
                    () -> orderExecution.execute(req, idempotencyKey));
            if (r.value().success() && !r.replay()) notifyOrderAccepted(req, r.value());
            return ResponseEntity.ok()
                    .header("X-Idempotent-Replay", String.valueOf(r.replay()))
                    .body(r.value());
        }

        OrderResult result = orderExecution.execute(req, null);
        if (result.success()) notifyOrderAccepted(req, result);
        return ResponseEntity.ok(result);
    }

    private void notifyOrderAccepted(OrderRequest req, OrderResult result) {
        String subject = String.format("[주문 접수] %s %s %d주 (%s)",
                req.getMarket().name(), req.getSymbol(), req.getQuantity(),
                req.getSide().toUpperCase());
        String html = "<div style='font-family:sans-serif'>"
                + "<h3>주문이 KIS에 접수되었습니다</h3>"
                + "<table style='border-collapse:collapse'>"
                + row("시장", req.getMarket().name())
                + row("종목", req.getSymbol())
                + row("거래소", req.getExchange() == null ? "-" : req.getExchange())
                + row("매수/매도", req.getSide())
                + row("주문 유형", req.getOrderType())
                + row("수량", String.valueOf(req.getQuantity()))
                + row("가격", req.getPrice() == null ? "시장가(지정가 변환)" : req.getPrice())
                + row("주문번호", result.orderNo())
                + row("접수시각", result.orderTime())
                + "</table>"
                + "<p style='color:#999;font-size:12px'>체결 여부는 별도 확인 필요.</p>"
                + "</div>";
        emailService.sendHtml(subject, html);
    }

    private static String row(String k, String v) {
        return "<tr><td style='padding:4px 12px 4px 0;color:#666'>" + k + "</td>"
                + "<td style='padding:4px 0'><b>" + (v == null ? "-" : v) + "</b></td></tr>";
    }

    // ========== 수급 리포트 (수동 트리거) — ADMIN 만 ==========

    @PostMapping("/report/supply-demand/run")
    public Map<String, String> runSupplyDemandReportNow() {
        reportJob.runNow();
        return Map.of("message", "supply-demand report triggered; check email inbox");
    }

    @PostMapping("/report/weekly/run")
    public Map<String, String> runWeeklyReportNow() {
        weeklyReport.runNow();
        return Map.of("message", "weekly report triggered; check email inbox");
    }

    @PostMapping("/dart/brief")
    public Map<String, String> runDartBriefNow() {
        dartService.briefNow();
        return Map.of("message", "DART 브리핑 트리거됨. 메일 확인 (호재/악재 분류된 공시가 있으면 발송).");
    }

    @GetMapping("/scanner/volume")
    public Map<String, Object> volumeScannerStatus() {
        return volumeScanner.status();
    }

    @PostMapping("/scanner/volume/enabled")
    public Map<String, Object> setVolumeScannerEnabled(@RequestParam boolean value) {
        volumeScanner.setEnabled(value);
        return volumeScanner.status();
    }

    @GetMapping("/loss-guard")
    public Map<String, Object> lossGuardStatus() {
        return Map.of(
                "netRealizedKrw", lossGuard.getNetRealizedKrw(),
                "limitKrw", lossGuard.getLimitKrw(),
                "buyAllowed", lossGuard.isBuyAllowed()
        );
    }

    // ========== OCO (자동 손절/익절) — 국내 단타 전용 ==========

    @PostMapping("/oco")
    public OcoPosition registerOco(@Valid @RequestBody OcoRequest req) {
        return ocoService.register(req);
    }

    @GetMapping("/oco")
    public List<OcoPosition> listOco() {
        return ocoService.list().stream().toList();
    }

    @GetMapping("/oco/{id}")
    public ResponseEntity<OcoPosition> getOco(@PathVariable @Size(max = 64) String id) {
        OcoPosition p = ocoService.get(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @DeleteMapping("/oco/{id}")
    public OcoPosition cancelOco(@PathVariable @Size(max = 64) String id) {
        return ocoService.cancel(id);
    }

    @GetMapping("/debug/investor-flow")
    public Map<String, Object> debugInvestorFlow(
            @RequestParam(defaultValue = "005930")
            @Pattern(regexp = "^\\d{6}$", message = "code 는 6자리 숫자") String code) {
        var inv = kisMarketData.getInvestorFlow(code, code,
                java.time.LocalDate.now().minusDays(10), java.time.LocalDate.now());
        return Map.of(
                "code", code,
                "rowCount", inv.getDailyData().size(),
                "rows", inv.getDailyData().stream().map(d -> Map.of(
                        "date", d.getDate().toString(),
                        "closePrice", d.getClosePrice(),
                        "individualNetQty", d.getIndividualNet(),
                        "foreignNetQty", d.getForeignNet(),
                        "institutionNetQty", d.getInstitutionNet(),
                        "individualNetAmt_raw", d.getIndividualNetAmt(),
                        "foreignNetAmt_raw", d.getForeignNetAmt(),
                        "institutionNetAmt_raw", d.getInstitutionNetAmt()
                )).toList(),
                "turnover_raw", kisMarketData.getDomesticTurnoverWon(code)
        );
    }

    @PostMapping("/alert/test-email")
    public Map<String, Object> testEmail() {
        if (!emailService.isConfigured()) {
            return Map.of("ok", false, "reason",
                    "GMAIL_USERNAME / GMAIL_APP_PASSWORD / ALERT_RECIPIENT 환경변수를 설정하세요");
        }
        emailService.sendHtml("[테스트] 백테스팅 툴 알림 연결 확인",
                "<p>이 메일이 보이면 Gmail 알림 연결이 정상입니다.</p>");
        return Map.of("ok", true, "message", "테스트 메일 전송 요청 완료. 메일함 확인하세요.");
    }

    @GetMapping("/orders/open")
    public List<Map<String, Object>> openOrders() {
        return kisTrading.getOpenOrders();
    }

    // ========== 잔고 ==========

    @GetMapping("/balance")
    public BalanceResult balance(
            @RequestParam @NotNull
            @Pattern(regexp = "^(KR_STOCK|US_STOCK|JP_STOCK)$") String market) {
        return kisTrading.getBalance(AssetType.valueOf(market));
    }

    private List<Map<String, String>> symbols(Collection<MarketSymbol> syms) {
        return syms.stream().map(s -> Map.of(
                "market", s.market().name(),
                "code", s.code(),
                "exchange", s.exchange(),
                "currency", s.currency()
        )).toList();
    }
}

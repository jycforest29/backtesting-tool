package com.backtesting.controller;

import com.backtesting.model.*;
import com.backtesting.service.AuditLogService;
import com.backtesting.service.BacktestService;
import com.backtesting.service.PortfolioService;
import com.backtesting.service.StressTestService;
import com.backtesting.service.kis.KisMarketDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 / 포트폴리오 / 검색 / 스트레스테스트 / 감사로그 읽기.
 *
 * 에러 처리:
 *   - 컨트롤러에서는 try/catch 금지. GlobalExceptionHandler 가 통일된 ErrorResponse 로 변환.
 *   - 도메인 검증 실패는 IllegalArgumentException 으로 전달 — 400 BAD_REQUEST 로 매핑.
 *   - 필드 형식 검증 실패는 @Valid → MethodArgumentNotValidException → 400 VALIDATION_FAILED.
 */
@RestController
@RequestMapping("/api")
@Validated   // @RequestParam 단위 제약 (@Pattern 등) 활성화용
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;
    private final PortfolioService portfolioService;
    private final KisMarketDataService marketData;
    private final AuditLogService auditLogService;
    private final StressTestService stressTestService;

    @PostMapping("/backtest")
    public BacktestResult backtest(@Valid @RequestBody BacktestRequest request) {
        return backtestService.calculate(request);
    }

    @PostMapping("/portfolio-backtest")
    public PortfolioBacktestResult portfolioBacktest(@Valid @RequestBody PortfolioBacktestRequest request) {
        return portfolioService.calculate(request);
    }

    /**
     * 종목 검증 / 간이 검색. market (KR_STOCK|US_STOCK|JP_STOCK) 필수.
     * KIS는 실시간 종목명 검색 API가 따로 없으므로, 사용자가 입력한 코드를 현재가로 조회해 유효성 확인.
     */
    @GetMapping("/search")
    public Object search(@RequestParam @NotBlank @Size(max = 20)
                         @Pattern(regexp = "^[A-Za-z0-9.\\-^]{1,20}$",
                                 message = "q 는 영숫자/. 1~20자") String q,
                         @RequestParam(required = false)
                         @Pattern(regexp = "^(KR_STOCK|US_STOCK|JP_STOCK|CRYPTO)?$") String market) {
        AssetType m = market != null && !market.isEmpty() ? AssetType.valueOf(market) : AssetType.KR_STOCK;
        return marketData.search(q, m);
    }

    @GetMapping("/investor-flow")
    public InvestorTrading getInvestorFlow(
            @RequestParam @Pattern(regexp = "^\\d{6}$", message = "stockCode 는 6자리 숫자") String stockCode,
            @RequestParam(required = false) @Size(max = 100) String stockName,
            @RequestParam @NotBlank String startDate,
            @RequestParam @NotBlank String endDate) {
        return marketData.getInvestorFlow(
                stockCode, stockName, LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    @PostMapping("/stress-test")
    public StressTestResult stressTest(@Valid @RequestBody StressTestRequest request) {
        return stressTestService.calculate(request);
    }

    @GetMapping("/stress-test/presets")
    public Map<String, String> getStressPresets() {
        return stressTestService.getPresetScenarios();
    }

    @GetMapping("/audit-log")
    public List<AuditLogEntry> getAuditLog(
            @RequestParam(defaultValue = "100") @Positive int limit,
            @RequestParam(required = false) @Pattern(regexp = "^(INFO|WARN|ERROR)?$") String level,
            @RequestParam(required = false) @Size(max = 60) String action) {
        return auditLogService.getEntries(limit, level, action);
    }

    @GetMapping("/audit-log/stats")
    public Map<String, Object> getAuditStats() {
        return auditLogService.getStats();
    }
}

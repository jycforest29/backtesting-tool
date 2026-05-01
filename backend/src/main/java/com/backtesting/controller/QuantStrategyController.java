package com.backtesting.controller;

import com.backtesting.config.DartProperties;
import com.backtesting.model.quant.*;
import com.backtesting.service.dart.DartCorpCodeService;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import com.backtesting.service.quant.FactorStrategy;
import com.backtesting.service.quant.QuantBacktestService;
import com.backtesting.service.quant.QuantExecutionPublisher;
import com.backtesting.service.quant.QuantExecutionService;
import com.backtesting.service.quant.QuantStrategy;
import com.backtesting.service.quant.QuantStrategyRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/quant")
@Validated
@RequiredArgsConstructor
public class QuantStrategyController {

    private final QuantStrategyRegistry registry;
    private final QuantBacktestService backtestService;
    private final QuantExecutionService executionService;
    private final QuantExecutionPublisher executionPublisher;
    private final DartProperties dartProps;
    private final DartCorpCodeService corpCodeService;
    private final FundamentalDataService fundamentalDataService;
    private final KospiUniverseService universeService;

    @GetMapping("/strategies")
    public List<Map<String, Object>> listStrategies() {
        // 메타데이터 응답이 한 전략의 외부 의존성(예: DART 키 미설정) 때문에 통째로 죽지 않도록
        // 각 전략의 defaultUniverse() 호출을 격리한다. 실패 시 빈 유니버스로 폴백 + WARN.
        return registry.all().stream().map(s -> {
            boolean factor = s instanceof FactorStrategy;
            List<QuantAsset> universe = factor ? List.of() : safeDefaultUniverse(s);
            return (Map<String, Object>) Map.of(
                    "type", s.type().name(),
                    "name", s.displayName(),
                    "description", s.description(),
                    "factor", factor,
                    "defaultUniverse", universe
            );
        }).toList();
    }

    private List<QuantAsset> safeDefaultUniverse(QuantStrategy s) {
        try {
            return s.defaultUniverse();
        } catch (RuntimeException ex) {
            log.warn("Strategy {} defaultUniverse() failed; returning empty universe ({})",
                    s.type(), ex.getMessage());
            return List.of();
        }
    }

    @GetMapping("/dart-status")
    public Map<String, Object> dartStatus() {
        return Map.of(
                "configured", dartProps.isConfigured(),
                "corpCodeCached", corpCodeService.cachedCount(),
                "universeSize", universeService.all().size(),
                "fundamentalCacheSize", fundamentalDataService.cacheSize()
        );
    }

    /**
     * 팩터 전략의 현재 상위 N 종목 시그널 미리보기 (주문 안 함).
     * 계층 A 전략에 호출하면 IllegalArgumentException 으로 400 반환.
     */
    @GetMapping("/preview/{type}")
    public Map<String, Object> preview(@PathVariable QuantStrategyType type,
                                        @RequestParam(required = false) @Min(1) @Max(100) Integer topN) {
        QuantStrategy s = registry.get(type);
        if (!(s instanceof FactorStrategy)) {
            throw new IllegalArgumentException("계층 A 전략은 preview 대신 /run 엔드포인트 사용");
        }
        QuantSignal sig = s.generateSignal(s.defaultUniverse(), Map.of(),
                LocalDate.now(),
                new QuantStrategy.StrategyParams(topN, null, null));
        return Map.of(
                "strategy", s.displayName(),
                "asOfDate", sig.getAsOfDate(),
                "rationale", sig.getRationale(),
                "picks", sig.getDiagnostics(),
                "targetWeights", sig.getTargetWeights()
        );
    }

    @PostMapping("/dart-refresh")
    public Map<String, Object> refreshFundamentals() {
        fundamentalDataService.clearCache();
        return Map.of("message", "DART 펀더멘털 캐시 초기화됨");
    }

    @PostMapping("/backtest")
    public QuantBacktestResult backtest(@Valid @RequestBody QuantBacktestRequest request) {
        return backtestService.run(request);
    }

    @GetMapping("/state")
    public List<QuantStrategyState> listStates() {
        return executionService.listStates();
    }

    /** 실시간 timeline 백필 — 모든 전략의 최근 실행 이력을 시간 desc 평탄화. */
    @GetMapping("/executions")
    public List<QuantExecutionLog> listRecentExecutions(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return executionService.listRecentAll(limit);
    }

    /**
     * 실행 이벤트 SSE stream — 새 실행 발생 시 모든 구독자에게 push.
     * 클라이언트는 EventSource 로 구독, name="execution" 이벤트만 처리하면 됨.
     * 초기 백필은 GET /executions 로 별도 호출 (SSE 첫 프레임 비대화).
     */
    @GetMapping(value = "/executions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamExecutions() {
        return executionPublisher.subscribe();
    }

    @PostMapping("/enable/{type}")
    public QuantStrategyState enable(@PathVariable QuantStrategyType type,
                                      @Valid @RequestBody QuantEnableRequest req) {
        return executionService.enable(type, req);
    }

    /**
     * 일임 종료. 옵션:
     *   - liquidate=false (기본)         : 비활성화만, 보유 포지션은 그대로 (사용자 수동 매도 가이드).
     *   - liquidate=true & dryRun=true   : 청산 미리보기 (실주문 없음, enabled 유지). [✓ 확정] 흐름용.
     *   - liquidate=true & dryRun=false  : 비활성화 → 청산. 청산 도중 스케줄러 재발화 방지 위해 disable 먼저.
     *
     * 응답은 항상 { state: ..., liquidation: log | null }. 미리보기는 enabled=true 그대로 유지된 state.
     */
    @PostMapping("/disable/{type}")
    public Map<String, Object> disable(
            @PathVariable QuantStrategyType type,
            @RequestParam(defaultValue = "false") boolean liquidate,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        if (!liquidate) {
            return Map.of("state", executionService.disable(type));
        }
        if (dryRun) {
            // 미리보기 — enabled 변경 안 하고 청산 outcome 만 반환.
            QuantExecutionLog preview = executionService.liquidate(type, true);
            return Map.of("state", executionService.getState(type), "liquidation", preview);
        }
        // 실청산: 옵션 4번 결정 — disable 먼저 → 그 뒤 청산.
        QuantStrategyState state = executionService.disable(type);
        QuantExecutionLog log = executionService.liquidate(type, false);
        return Map.of("state", state, "liquidation", log);
    }

    /**
     * 수동 리밸런싱 실행.
     * kind: MANUAL (실주문) / DRY_RUN (시그널만).
     * force=true 면 타이밍(월말) 무시하고 즉시 실행.
     */
    @PostMapping("/run/{type}")
    public ResponseEntity<?> runNow(@PathVariable QuantStrategyType type,
                                     @RequestParam(defaultValue = "DRY_RUN")
                                     @Pattern(regexp = "^(?i)(MANUAL|DRY_RUN)$") String kind,
                                     @RequestParam(defaultValue = "true") boolean force) {
        QuantExecutionLog.ExecutionKind k = QuantExecutionLog.ExecutionKind.valueOf(kind.toUpperCase());
        QuantExecutionLog log = executionService.runStrategy(type, k, !force);
        if (log == null) {
            return ResponseEntity.ok(Map.of(
                    "message", "오늘은 이 전략의 리밸런싱 일자가 아닙니다. force=true 로 강제 실행 가능."));
        }
        return ResponseEntity.ok(log);
    }
}

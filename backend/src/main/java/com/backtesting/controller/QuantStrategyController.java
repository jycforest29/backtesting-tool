package com.backtesting.controller;

import com.backtesting.config.DartProperties;
import com.backtesting.model.quant.*;
import com.backtesting.service.dart.DartCorpCodeService;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import com.backtesting.service.quant.FactorStrategy;
import com.backtesting.service.quant.QuantBacktestService;
import com.backtesting.service.quant.QuantExecutionService;
import com.backtesting.service.quant.QuantStrategy;
import com.backtesting.service.quant.QuantStrategyRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quant")
@Validated
@RequiredArgsConstructor
public class QuantStrategyController {

    private final QuantStrategyRegistry registry;
    private final QuantBacktestService backtestService;
    private final QuantExecutionService executionService;
    private final DartProperties dartProps;
    private final DartCorpCodeService corpCodeService;
    private final FundamentalDataService fundamentalDataService;
    private final KospiUniverseService universeService;

    @GetMapping("/strategies")
    public List<Map<String, Object>> listStrategies() {
        return registry.all().stream().map(s -> {
            boolean factor = s instanceof FactorStrategy;
            return (Map<String, Object>) Map.of(
                    "type", s.type().name(),
                    "name", s.displayName(),
                    "description", s.description(),
                    "factor", factor,
                    "defaultUniverse", factor ? List.of() : s.defaultUniverse()
            );
        }).toList();
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

    @PostMapping("/enable/{type}")
    public QuantStrategyState enable(@PathVariable QuantStrategyType type,
                                      @Valid @RequestBody QuantEnableRequest req) {
        return executionService.enable(type, req);
    }

    @PostMapping("/disable/{type}")
    public QuantStrategyState disable(@PathVariable QuantStrategyType type) {
        return executionService.disable(type);
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

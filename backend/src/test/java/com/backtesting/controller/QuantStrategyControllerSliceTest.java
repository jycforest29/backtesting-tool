package com.backtesting.controller;

import com.backtesting.common.error.GlobalExceptionHandler;
import com.backtesting.config.DartProperties;
import com.backtesting.model.quant.QuantEnableRequest;
import com.backtesting.model.quant.QuantExecutionLog;
import com.backtesting.model.quant.QuantStrategyState;
import com.backtesting.model.quant.QuantStrategyType;
import com.backtesting.service.dart.DartCorpCodeService;
import com.backtesting.service.dart.FundamentalDataService;
import com.backtesting.service.dart.KospiUniverseService;
import com.backtesting.service.quant.QuantBacktestService;
import com.backtesting.service.quant.QuantExecutionPublisher;
import com.backtesting.service.quant.QuantExecutionService;
import com.backtesting.service.quant.QuantStrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일임 라이프사이클 (미리보기 → 확정 → 종료) 컨트롤러 슬라이스.
 *
 * standalone MockMvc — 스프링 컨텍스트 로딩 없이 컨트롤러 + GlobalExceptionHandler 만 엮음.
 * 외부 시스템 (KIS / DART / 메일 / DB) 은 모두 Mockito mock.
 *
 *  - DRY_RUN run → executionService.runStrategy(_, DRY_RUN, !force) 호출 위임 검증
 *  - MANUAL run  → executionService.runStrategy(_, MANUAL, !force) 호출 위임 검증
 *  - runStrategy null 반환 (타이밍 안 맞음) → 안내 메시지 200
 *  - enable body validation 누락 → 400 BAD_REQUEST + VALIDATION_FAILED
 *  - disable → executionService.disable 호출 위임 검증
 */
class QuantStrategyControllerSliceTest {

    private MockMvc mvc;
    private QuantExecutionService executionService;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        executionService = mock(QuantExecutionService.class);

        QuantStrategyController controller = new QuantStrategyController(
                mock(QuantStrategyRegistry.class),
                mock(QuantBacktestService.class),
                executionService,
                mock(QuantExecutionPublisher.class),
                mock(DartProperties.class),
                mock(DartCorpCodeService.class),
                mock(FundamentalDataService.class),
                mock(KospiUniverseService.class)
        );

        // JSON 직렬화 시 LocalDateTime 등을 ISO-8601 로 처리. JavaTimeModule 등록 필수.
        MappingJackson2HttpMessageConverter conv = new MappingJackson2HttpMessageConverter(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(conv)
                .build();
    }

    private QuantExecutionLog manualLog() {
        return QuantExecutionLog.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .kind(QuantExecutionLog.ExecutionKind.MANUAL)
                .executedAt(LocalDateTime.of(2026, 4, 30, 15, 25))
                .orders(List.of())
                .build();
    }

    private QuantExecutionLog dryRunLog() {
        return QuantExecutionLog.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .kind(QuantExecutionLog.ExecutionKind.DRY_RUN)
                .executedAt(LocalDateTime.of(2026, 4, 30, 15, 25))
                .orders(List.of())
                .build();
    }

    @Test
    void runDryRunDelegatesToServiceWithDryRunKind() throws Exception {
        when(executionService.runStrategy(eq(QuantStrategyType.DUAL_MOMENTUM),
                eq(QuantExecutionLog.ExecutionKind.DRY_RUN), eq(false)))
                .thenReturn(dryRunLog());

        mvc.perform(post("/api/quant/run/DUAL_MOMENTUM")
                        .param("kind", "DRY_RUN")
                        .param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DRY_RUN"));

        // force=true → respectTiming=false 로 호출되어야 함.
        verify(executionService).runStrategy(QuantStrategyType.DUAL_MOMENTUM,
                QuantExecutionLog.ExecutionKind.DRY_RUN, false);
    }

    @Test
    void runManualConfirmsAfterPreview() throws Exception {
        when(executionService.runStrategy(eq(QuantStrategyType.DUAL_MOMENTUM),
                eq(QuantExecutionLog.ExecutionKind.MANUAL), eq(false)))
                .thenReturn(manualLog());

        mvc.perform(post("/api/quant/run/DUAL_MOMENTUM")
                        .param("kind", "MANUAL")
                        .param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("MANUAL"));

        verify(executionService).runStrategy(QuantStrategyType.DUAL_MOMENTUM,
                QuantExecutionLog.ExecutionKind.MANUAL, false);
    }

    @Test
    void runReturnsScheduleSkipMessageWhenServiceReturnsNull() throws Exception {
        when(executionService.runStrategy(any(), any(), eq(true))).thenReturn(null);

        mvc.perform(post("/api/quant/run/DUAL_MOMENTUM")
                        .param("kind", "DRY_RUN")
                        .param("force", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("오늘은")));
    }

    @Test
    void enableDelegatesToServiceAndReturnsState() throws Exception {
        QuantStrategyState state = QuantStrategyState.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .strategyName("듀얼 모멘텀")
                .enabled(true)
                .allocatedAmount(new BigDecimal("1000000"))
                .build();
        when(executionService.enable(eq(QuantStrategyType.DUAL_MOMENTUM), any(QuantEnableRequest.class)))
                .thenReturn(state);

        String body = """
                { "allocatedAmount": 1000000 }
                """;

        mvc.perform(post("/api/quant/enable/DUAL_MOMENTUM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allocatedAmount").value(1000000));

        verify(executionService).enable(eq(QuantStrategyType.DUAL_MOMENTUM), any(QuantEnableRequest.class));
    }

    @Test
    void enableRejectsMissingAllocatedAmount() throws Exception {
        // @NotNull / @DecimalMin("1.0") on allocatedAmount — body 검증 실패는 400.
        mvc.perform(post("/api/quant/enable/DUAL_MOMENTUM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(executionService, never()).enable(any(), any());
    }

    @Test
    void enableRejectsZeroOrNegativeAllocation() throws Exception {
        mvc.perform(post("/api/quant/enable/DUAL_MOMENTUM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"allocatedAmount\": 0 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(executionService, never()).enable(any(), any());
    }

    @Test
    void disableWithoutLiquidateOnlyChangesState() throws Exception {
        QuantStrategyState state = QuantStrategyState.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .strategyName("듀얼 모멘텀")
                .enabled(false)
                .build();
        when(executionService.disable(QuantStrategyType.DUAL_MOMENTUM)).thenReturn(state);

        mvc.perform(post("/api/quant/disable/DUAL_MOMENTUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.enabled").value(false))
                .andExpect(jsonPath("$.liquidation").doesNotExist());

        verify(executionService).disable(QuantStrategyType.DUAL_MOMENTUM);
        verify(executionService, never()).liquidate(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void disableWithLiquidateDryRunPreviewsWithoutDisabling() throws Exception {
        // 미리보기는 enabled=true 그대로 유지 + 청산 outcome 반환.
        QuantStrategyState alive = QuantStrategyState.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .strategyName("듀얼 모멘텀")
                .enabled(true)
                .build();
        QuantExecutionLog preview = QuantExecutionLog.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .kind(QuantExecutionLog.ExecutionKind.LIQUIDATION)
                .executedAt(LocalDateTime.of(2026, 4, 30, 15, 25))
                .orders(List.of())
                .build();
        when(executionService.liquidate(QuantStrategyType.DUAL_MOMENTUM, true)).thenReturn(preview);
        when(executionService.getState(QuantStrategyType.DUAL_MOMENTUM)).thenReturn(alive);

        mvc.perform(post("/api/quant/disable/DUAL_MOMENTUM")
                        .param("liquidate", "true")
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.enabled").value(true))
                .andExpect(jsonPath("$.liquidation.kind").value("LIQUIDATION"));

        verify(executionService).liquidate(QuantStrategyType.DUAL_MOMENTUM, true);
        // 미리보기는 disable 호출 없음 — 사용자가 [✓ 확정] 누르기 전에는 상태 유지.
        verify(executionService, never()).disable(any());
    }

    @Test
    void disableWithLiquidateConfirmDisablesThenLiquidates() throws Exception {
        // 옵션 4번 결정: disable 먼저 (스케줄러 재발화 방지) → 청산.
        QuantStrategyState dead = QuantStrategyState.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .strategyName("듀얼 모멘텀")
                .enabled(false)
                .build();
        QuantExecutionLog liquidationLog = QuantExecutionLog.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .kind(QuantExecutionLog.ExecutionKind.LIQUIDATION)
                .executedAt(LocalDateTime.of(2026, 4, 30, 15, 25))
                .orders(List.of())
                .build();
        when(executionService.disable(QuantStrategyType.DUAL_MOMENTUM)).thenReturn(dead);
        when(executionService.liquidate(QuantStrategyType.DUAL_MOMENTUM, false)).thenReturn(liquidationLog);

        mvc.perform(post("/api/quant/disable/DUAL_MOMENTUM")
                        .param("liquidate", "true")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.enabled").value(false))
                .andExpect(jsonPath("$.liquidation.kind").value("LIQUIDATION"));

        // 호출 순서 검증 — disable 먼저, liquidate 나중.
        org.mockito.InOrder ord = org.mockito.Mockito.inOrder(executionService);
        ord.verify(executionService).disable(QuantStrategyType.DUAL_MOMENTUM);
        ord.verify(executionService).liquidate(QuantStrategyType.DUAL_MOMENTUM, false);
    }
}

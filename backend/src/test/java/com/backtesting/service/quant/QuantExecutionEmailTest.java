package com.backtesting.service.quant;

import com.backtesting.config.KisProperties;
import com.backtesting.model.quant.QuantExecutionLog;
import com.backtesting.model.quant.QuantStrategyType;
import com.backtesting.persistence.StrategyConfigRepository;
import com.backtesting.persistence.StrategyExecutionRepository;
import com.backtesting.service.DailyLossGuard;
import com.backtesting.service.EmailService;
import com.backtesting.service.kis.KisMarketDataService;
import com.backtesting.service.kis.KisTradingService;
import com.backtesting.service.kis.KrxCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 메일 발송 분기 단위 테스트.
 *
 *  - DRY_RUN 미리보기 → 메일 차단 (R4 의도: 미리보기 noise 회피)
 *  - 메일 미설정 (isConfigured=false) → early return
 *  - MANUAL 정상 → subject 에 "수동 리밸런싱 완료" + 매수/매도 카운트
 *  - SCHEDULED 정상 → subject 에 "자동 리밸런싱 완료"
 *  - LossGuard 차단 outcome 포함 → subject 에 ⚠ + "손실 한도 차단" (R5)
 *  - 오류 outcome → subject 에 "오류"
 */
class QuantExecutionEmailTest {

    private KisMarketDataService marketData;
    private KisTradingService kisTrading;
    private KisProperties kisProps;
    private DailyLossGuard lossGuard;
    private EmailService emailService;
    private QuantStrategyRegistry registry;
    private StrategyConfigRepository configRepo;
    private StrategyExecutionRepository execRepo;
    private QuantExecutionPublisher executionPublisher;
    private KrxCalendarService krxCalendar;

    private QuantStrategy strat;
    private QuantExecutionService service;

    @BeforeEach
    void setUp() {
        marketData = mock(KisMarketDataService.class);
        kisTrading = mock(KisTradingService.class);
        kisProps = mock(KisProperties.class);
        lossGuard = mock(DailyLossGuard.class);
        emailService = mock(EmailService.class);
        registry = mock(QuantStrategyRegistry.class);
        configRepo = mock(StrategyConfigRepository.class);
        execRepo = mock(StrategyExecutionRepository.class);
        executionPublisher = mock(QuantExecutionPublisher.class);
        krxCalendar = mock(KrxCalendarService.class);

        // restoreFromDb (PostConstruct) 우회 — findAll 빈 리스트로 + KIS configured=true 로 autoDisable 스킵.
        when(configRepo.findAll()).thenReturn(List.of());
        when(kisProps.isConfigured()).thenReturn(true);
        when(emailService.isConfigured()).thenReturn(true);

        strat = mock(QuantStrategy.class);
        when(strat.displayName()).thenReturn("듀얼 모멘텀");

        service = new QuantExecutionService(
                marketData, kisTrading, kisProps, lossGuard, emailService,
                registry, configRepo, execRepo, executionPublisher, krxCalendar);
    }

    private QuantExecutionLog logOf(QuantExecutionLog.ExecutionKind kind,
                                    List<QuantExecutionLog.OrderOutcome> orders,
                                    String errorMessage) {
        return QuantExecutionLog.builder()
                .strategyType(QuantStrategyType.DUAL_MOMENTUM)
                .kind(kind)
                .executedAt(LocalDateTime.of(2026, 4, 30, 15, 25))
                .orders(orders)
                .errorMessage(errorMessage)
                .build();
    }

    private QuantExecutionLog.OrderOutcome buy() {
        return QuantExecutionLog.OrderOutcome.builder()
                .symbol("069500").name("KODEX 200").side("BUY").quantity(10)
                .success(true).build();
    }

    private QuantExecutionLog.OrderOutcome sell() {
        return QuantExecutionLog.OrderOutcome.builder()
                .symbol("114800").name("KODEX 인버스").side("SELL").quantity(5)
                .success(true).build();
    }

    private QuantExecutionLog.OrderOutcome lossGuardBlocked() {
        // executeRebalance 가 차단 시 message 에 박는 "손실 한도" 문자열로 식별.
        return QuantExecutionLog.OrderOutcome.builder()
                .symbol("069500").name("KODEX 200").side("BUY").quantity(10)
                .success(false).message("일일 손실 한도 초과로 매수 차단").build();
    }

    @Test
    void dryRunSkipsEmail() {
        // R4: DRY_RUN 미리보기는 메일 noise — sendExecutionEmail early return 해야 함.
        QuantExecutionLog entry = logOf(QuantExecutionLog.ExecutionKind.DRY_RUN, List.of(buy()), null);
        service.sendExecutionEmail(QuantStrategyType.DUAL_MOMENTUM, strat, entry);
        verify(emailService, never()).sendHtml(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void emailNotConfiguredSkipsSend() {
        when(emailService.isConfigured()).thenReturn(false);
        QuantExecutionLog entry = logOf(QuantExecutionLog.ExecutionKind.MANUAL, List.of(buy()), null);
        service.sendExecutionEmail(QuantStrategyType.DUAL_MOMENTUM, strat, entry);
        verify(emailService, never()).sendHtml(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void manualSuccessSubjectContainsBuySellCounts() {
        QuantExecutionLog entry = logOf(QuantExecutionLog.ExecutionKind.MANUAL,
                List.of(buy(), buy(), sell()), null);

        service.sendExecutionEmail(QuantStrategyType.DUAL_MOMENTUM, strat, entry);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendHtml(subject.capture(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(subject.getValue())
                .contains("듀얼 모멘텀")
                .contains("수동 리밸런싱 완료")
                .contains("매수 2")
                .contains("매도 1");
    }

    @Test
    void scheduledSuccessSubjectSaysAuto() {
        QuantExecutionLog entry = logOf(QuantExecutionLog.ExecutionKind.SCHEDULED,
                List.of(buy()), null);

        service.sendExecutionEmail(QuantStrategyType.DUAL_MOMENTUM, strat, entry);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(subject.capture(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(subject.getValue()).contains("자동 리밸런싱 완료");
    }

    @Test
    void lossGuardBlockedRaisesWarningSubject() {
        // R5: 손실 한도 차단은 운영자 즉시 인지가 필요한 사건. subject 에 ⚠ + "손실 한도 차단".
        QuantExecutionLog entry = logOf(QuantExecutionLog.ExecutionKind.MANUAL,
                List.of(lossGuardBlocked(), sell()), null);

        service.sendExecutionEmail(QuantStrategyType.DUAL_MOMENTUM, strat, entry);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(subject.capture(), body.capture());

        assertThat(subject.getValue())
                .contains("⚠")
                .contains("손실 한도 차단")
                .contains("매수 1건 스킵")
                .contains("매도 1건만 실행");
        // 본문에도 빨간 배너로 동일 사건 강조.
        assertThat(body.getValue())
                .contains("일일 손실 한도 차단 발생")
                .contains("FEF2F2");  // 빨간 배경 hex
    }

    @Test
    void errorEntrySubjectMarksFailure() {
        QuantExecutionLog entry = logOf(QuantExecutionLog.ExecutionKind.MANUAL,
                List.of(), "가격 조회 실패: KIS timeout");

        service.sendExecutionEmail(QuantStrategyType.DUAL_MOMENTUM, strat, entry);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(subject.capture(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(subject.getValue()).contains("수동 리밸런싱 오류");
    }
}

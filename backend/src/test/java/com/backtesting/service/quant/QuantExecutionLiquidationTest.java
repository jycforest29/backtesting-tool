package com.backtesting.service.quant;

import com.backtesting.config.KisProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.BalanceResult;
import com.backtesting.model.OrderRequest;
import com.backtesting.model.OrderResult;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantEnableRequest;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R7a — 일임 종료 시 청산(liquidate) 흐름 단위 테스트.
 *
 *  - dryRun=true       : 실주문 호출 없음, outcome 만 미리보기로
 *  - dryRun=false      : 보유 심볼 전량 시장가 SELL
 *  - 유니버스 외 보유   : 청산 대상 제외 (다른 전략·수동 매수 보호)
 *  - quantity=0 보유    : 스킵
 *  - 부분 실패          : best-effort — 한 종목 실패가 다른 종목 차단 안 함
 *  - 잔고 조회 실패     : errorMessage 로 기록, outcome 빈 리스트
 *  - 청산 메일 subject  : LIQUIDATION 라벨 + 잔여 시 ⚠
 */
class QuantExecutionLiquidationTest {

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

    private static final QuantStrategyType TYPE = QuantStrategyType.DUAL_MOMENTUM;

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

        when(configRepo.findAll()).thenReturn(List.of());
        when(kisProps.isConfigured()).thenReturn(true);
        when(emailService.isConfigured()).thenReturn(true);

        strat = mock(QuantStrategy.class);
        when(strat.displayName()).thenReturn("듀얼 모멘텀");
        when(strat.defaultUniverse()).thenReturn(List.of(
                new QuantAsset("069500", "KODEX 200", AssetType.KR_STOCK, null, null),
                new QuantAsset("114800", "KODEX 인버스", AssetType.KR_STOCK, null, null)
        ));
        when(registry.get(TYPE)).thenReturn(strat);

        service = new QuantExecutionService(
                marketData, kisTrading, kisProps, lossGuard, emailService,
                registry, configRepo, execRepo, executionPublisher, krxCalendar);

        // enable 으로 state 등록 (allocated 0 회피 → 양수).
        QuantEnableRequest req = new QuantEnableRequest();
        req.setAllocatedAmount(new BigDecimal("1000000"));
        service.enable(TYPE, req);

        // enable 이 ENABLED lifecycle 메일을 1번 보냈으므로 청산 검증 카운트가 오염됨 — 정리.
        org.mockito.Mockito.clearInvocations(emailService);
    }

    private BalanceResult.Holding holding(String symbol, String name, long qty, String price, String evalAmt) {
        return BalanceResult.Holding.builder()
                .symbol(symbol).name(name)
                .quantity(qty)
                .currentPrice(new BigDecimal(price))
                .evalAmount(new BigDecimal(evalAmt))
                .build();
    }

    private BalanceResult balanceOf(BalanceResult.Holding... hs) {
        return BalanceResult.builder()
                .market(AssetType.KR_STOCK)
                .holdings(List.of(hs))
                .build();
    }

    @Test
    void dryRunDoesNotPlaceOrdersAndDoesNotEmail() {
        when(kisTrading.getBalance(AssetType.KR_STOCK)).thenReturn(
                balanceOf(holding("069500", "KODEX 200", 10, "30000", "300000")));

        QuantExecutionLog log = service.liquidate(TYPE, true);

        assertThat(log.getKind()).isEqualTo(QuantExecutionLog.ExecutionKind.LIQUIDATION);
        assertThat(log.getOrders()).hasSize(1);
        assertThat(log.getOrders().get(0).getSide()).isEqualTo("SELL");
        assertThat(log.getOrders().get(0).getMessage()).contains("드라이런");

        verify(kisTrading, never()).placeOrder(any(OrderRequest.class));
        // DRY_RUN 기준 적용 — 미리보기는 메일 안 감.
        verify(emailService, never()).sendHtml(anyString(), anyString());
    }

    @Test
    void confirmedLiquidationPlacesSellOrdersForUniverseHoldings() {
        when(kisTrading.getBalance(AssetType.KR_STOCK)).thenReturn(
                balanceOf(
                        holding("069500", "KODEX 200", 10, "30000", "300000"),
                        holding("114800", "KODEX 인버스", 5, "5000", "25000")
                ));
        when(kisTrading.placeOrder(any(OrderRequest.class))).thenReturn(
                OrderResult.builder().success(true).orderNo("OK").build());
        when(kisTrading.getOpenOrders()).thenReturn(List.of());

        QuantExecutionLog log = service.liquidate(TYPE, false);

        assertThat(log.getOrders()).hasSize(2);
        assertThat(log.getOrders()).allMatch(o -> "SELL".equals(o.getSide()) && o.isSuccess());

        ArgumentCaptor<OrderRequest> cap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(kisTrading, times(2)).placeOrder(cap.capture());
        assertThat(cap.getAllValues()).allMatch(r -> "SELL".equals(r.getSide()) && "MARKET".equals(r.getOrderType()));
    }

    @Test
    void liquidationSkipsNonUniverseHoldings() {
        // 다른 전략·수동 매수 종목 (005930 삼전) 은 건드리지 않음.
        when(kisTrading.getBalance(AssetType.KR_STOCK)).thenReturn(
                balanceOf(
                        holding("069500", "KODEX 200", 10, "30000", "300000"),
                        holding("005930", "삼성전자", 100, "70000", "7000000")
                ));
        when(kisTrading.placeOrder(any(OrderRequest.class))).thenReturn(
                OrderResult.builder().success(true).orderNo("OK").build());
        when(kisTrading.getOpenOrders()).thenReturn(List.of());

        QuantExecutionLog log = service.liquidate(TYPE, false);

        assertThat(log.getOrders()).hasSize(1);
        assertThat(log.getOrders().get(0).getSymbol()).isEqualTo("069500");

        ArgumentCaptor<OrderRequest> cap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(kisTrading, times(1)).placeOrder(cap.capture());
        assertThat(cap.getValue().getSymbol()).isEqualTo("069500");
    }

    @Test
    void liquidationSkipsZeroQuantityHoldings() {
        when(kisTrading.getBalance(AssetType.KR_STOCK)).thenReturn(
                balanceOf(
                        holding("069500", "KODEX 200", 0, "30000", "0"),
                        holding("114800", "KODEX 인버스", 5, "5000", "25000")
                ));
        when(kisTrading.placeOrder(any(OrderRequest.class))).thenReturn(
                OrderResult.builder().success(true).orderNo("OK").build());
        when(kisTrading.getOpenOrders()).thenReturn(List.of());

        QuantExecutionLog log = service.liquidate(TYPE, false);

        assertThat(log.getOrders()).hasSize(1);
        assertThat(log.getOrders().get(0).getSymbol()).isEqualTo("114800");
    }

    @Test
    void partialFailureContinuesBestEffortAndRaisesWarningSubject() {
        when(kisTrading.getBalance(AssetType.KR_STOCK)).thenReturn(
                balanceOf(
                        holding("069500", "KODEX 200", 10, "30000", "300000"),
                        holding("114800", "KODEX 인버스", 5, "5000", "25000")
                ));
        when(kisTrading.getOpenOrders()).thenReturn(List.of());
        // 첫 주문 실패, 두번째 성공.
        when(kisTrading.placeOrder(any(OrderRequest.class)))
                .thenReturn(OrderResult.builder().success(false).message("거래 정지").build())
                .thenReturn(OrderResult.builder().success(true).orderNo("OK2").build());

        QuantExecutionLog log = service.liquidate(TYPE, false);

        assertThat(log.getOrders()).hasSize(2);  // best-effort: 두 종목 다 시도
        assertThat(log.getOrders().get(0).isSuccess()).isFalse();
        assertThat(log.getOrders().get(1).isSuccess()).isTrue();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(subject.capture(), body.capture());

        assertThat(subject.getValue())
                .contains("⚠")
                .contains("일임 종료 + 청산")
                .contains("매도 성공 1")
                .contains("실패 1")
                .contains("잔여 포지션 있음");
        assertThat(body.getValue()).contains("일부 매도 실패 — 잔여 포지션 1건");
    }

    @Test
    void successfulLiquidationSendsEmailWithCleanSubject() {
        when(kisTrading.getBalance(AssetType.KR_STOCK)).thenReturn(
                balanceOf(holding("069500", "KODEX 200", 10, "30000", "300000")));
        when(kisTrading.getOpenOrders()).thenReturn(List.of());
        when(kisTrading.placeOrder(any(OrderRequest.class))).thenReturn(
                OrderResult.builder().success(true).orderNo("OK").build());

        service.liquidate(TYPE, false);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(subject.capture(), anyString());
        assertThat(subject.getValue())
                .doesNotContain("⚠")
                .contains("일임 종료 + 청산 완료")
                .contains("매도 1건");
    }

    @Test
    void balanceQueryFailureRecordsErrorMessage() {
        when(kisTrading.getBalance(AssetType.KR_STOCK))
                .thenThrow(new RuntimeException("KIS 잔고 timeout"));

        QuantExecutionLog log = service.liquidate(TYPE, false);

        assertThat(log.getErrorMessage()).contains("청산 실행 실패").contains("KIS 잔고 timeout");
        assertThat(log.getOrders()).isEmpty();
        verify(kisTrading, never()).placeOrder(any(OrderRequest.class));
    }
}

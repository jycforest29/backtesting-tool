package com.backtesting.service;

import com.backtesting.config.AlertProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.InvestorTrading;
import com.backtesting.service.kis.KisMarketDataService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 외국인+기관 수급 스캐너.
 * 후보 풀 = 국내 마스터 종목 + 거래대금 필터.
 * 3기준:
 *   C1 개인 역방향 배수: (외국인+기관 순매수) >= |개인 순매도| × retailSellMultiplier
 *   C2 전일 대비 증폭: |오늘 외국인+기관 수급| >= |어제 외국인+기관 수급| × prevDayMultiplier
 *   C3 유동성: 거래대금 >= minTurnoverBillion 억원
 * minCriteriaPassed 개 이상 통과 시 후보. 매수폭·매도폭 각각 Top N 반환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplyDemandScannerService {

    private final StockMasterService masterService;
    private final KisMarketDataService marketData;
    private final AlertProperties alertProps;

    @Value
    @Builder
    public static class ScanRow {
        String code;
        String name;
        long foreignInstNet;      // 오늘(전 거래일) 외국인+기관 순매수 금액(원)
        long retailNet;           // 오늘 개인 순매수 금액(원)
        long prevForeignInstNet;  // 어제 외국인+기관 순매수 금액(원)
        long turnoverWon;         // 오늘 누적 거래대금(원)
        boolean criteriaRetailDivergence;
        boolean criteriaPrevDayAmplification;
        boolean criteriaTurnover;
        int criteriaPassed;

        public long turnoverBillion() { return turnoverWon / 100_000_000L; }
        public long foreignInstNetBillion() { return foreignInstNet / 100_000_000L; }
        public long retailNetBillion() { return retailNet / 100_000_000L; }
    }

    @Value
    @Builder
    public static class ScanReport {
        LocalDate tradingDate;
        List<ScanRow> topBuy;
        List<ScanRow> topSell;
        int candidatesScanned;
    }

    public ScanReport scan() {
        var scannerCfg = alertProps.getScanner();
        List<StockMasterService.StockEntry> pool = masterService.getAll(AssetType.KR_STOCK);
        int poolLimit = Math.min(scannerCfg.getCandidatePoolSize(), pool.size());
        pool = pool.subList(0, poolLimit);

        List<ScanRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(10); // 최근 2거래일 확보용 여유

        for (var entry : pool) {
            try {
                InvestorTrading inv = marketData.getInvestorFlow(
                        entry.code(), entry.name(), windowStart, today);
                if (inv.getDailyData().size() < 2) continue;

                // 가장 최근 거래일 2개
                var sorted = new ArrayList<>(inv.getDailyData());
                sorted.sort(Comparator.comparing(InvestorTrading.DailyTrading::getDate).reversed());
                var d0 = sorted.get(0); // 전 거래일 (오늘 아침 기준 "어제 장")
                var d1 = sorted.get(1); // 그 이전 거래일

                // KIS inquire-investor의 *_ntby_tr_pbmn은 백만원 단위 → 원으로 통일
                long forNet = d0.getForeignNetAmt().longValue() * 1_000_000L;
                long instNet = d0.getInstitutionNetAmt().longValue() * 1_000_000L;
                long indNet = d0.getIndividualNetAmt().longValue() * 1_000_000L;
                long smartNet = forNet + instNet;

                long prevForNet = d1.getForeignNetAmt().longValue() * 1_000_000L;
                long prevInstNet = d1.getInstitutionNetAmt().longValue() * 1_000_000L;
                long prevSmartNet = prevForNet + prevInstNet;

                long turnover = marketData.getDomesticTurnoverWon(entry.code());

                boolean c1, c2, c3;
                // C1: 외국인+기관과 개인이 '반대 방향'이면서 배수 조건 충족
                if (smartNet > 0 && indNet < 0) {
                    c1 = smartNet >= Math.abs(indNet) * scannerCfg.getRetailSellMultiplier();
                } else if (smartNet < 0 && indNet > 0) {
                    c1 = Math.abs(smartNet) >= indNet * scannerCfg.getRetailSellMultiplier();
                } else {
                    c1 = false;
                }
                // C2: 같은 방향 기준, 오늘 절대값이 어제의 배수 이상
                c2 = prevSmartNet != 0
                        && Math.abs(smartNet) >= Math.abs(prevSmartNet) * scannerCfg.getPrevDayMultiplier()
                        && Math.signum(smartNet) == Math.signum(prevSmartNet);
                // C3: 거래대금
                c3 = turnover >= scannerCfg.getMinTurnoverBillion() * 100_000_000L;

                int passed = (c1 ? 1 : 0) + (c2 ? 1 : 0) + (c3 ? 1 : 0);
                if (passed < scannerCfg.getMinCriteriaPassed()) continue;

                rows.add(ScanRow.builder()
                        .code(entry.code()).name(entry.name())
                        .foreignInstNet(smartNet)
                        .retailNet(indNet)
                        .prevForeignInstNet(prevSmartNet)
                        .turnoverWon(turnover)
                        .criteriaRetailDivergence(c1)
                        .criteriaPrevDayAmplification(c2)
                        .criteriaTurnover(c3)
                        .criteriaPassed(passed)
                        .build());
            } catch (Exception e) {
                log.debug("scan skip {}: {}", entry.code(), e.getMessage());
            }
        }

        // 매수 방향 Top N (smartNet 큰 순)
        List<ScanRow> topBuy = rows.stream()
                .filter(r -> r.getForeignInstNet() > 0)
                .sorted(Comparator.comparingLong(ScanRow::getForeignInstNet).reversed())
                .limit(scannerCfg.getTopN())
                .toList();
        // 매도 방향 Top N (smartNet 작은 순)
        List<ScanRow> topSell = rows.stream()
                .filter(r -> r.getForeignInstNet() < 0)
                .sorted(Comparator.comparingLong(ScanRow::getForeignInstNet))
                .limit(scannerCfg.getTopN())
                .toList();

        LocalDate tradingDate = rows.isEmpty()
                ? today.minusDays(1)
                : today.minusDays(1); // 실제 거래일은 scan 안에서 d0.date로 추출 가능하지만 메일에선 요약 수준

        return ScanReport.builder()
                .tradingDate(tradingDate)
                .topBuy(topBuy)
                .topSell(topSell)
                .candidatesScanned(pool.size())
                .build();
    }
}

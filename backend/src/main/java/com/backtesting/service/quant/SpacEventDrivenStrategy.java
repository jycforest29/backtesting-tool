package com.backtesting.service.quant;

import com.backtesting.model.AssetType;
import com.backtesting.model.quant.QuantAsset;
import com.backtesting.model.quant.QuantSignal;
import com.backtesting.model.quant.QuantStrategyType;
import com.backtesting.service.dart.SpacEventService;
import com.backtesting.service.dart.SpacUniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPAC 이벤트 드리븐.
 * KRX 상장 SPAC 유니버스에서 가격이 공모가(₩2,000) 근처 밴드 내이고
 * 최근 N일 내 합병 관련 공시가 없는 종목을 균등 매수.
 *
 * 청산 트리거:
 *  - 합병 공시 발생(최근 5거래일 내) → 다음 날 weight=0
 *  - 가격이 밴드 이탈(₩2,000 × 1.03 초과 또는 × 0.995 미만) → weight=0
 *
 * 한화투자증권은 KRX SPAC 상장 주관 상위권 → 동일 유니버스를 한화 계좌로 매매 가능.
 * 상장 폐지/합병 완료 종목은 KIS 가격 조회 실패 → 백테스트 엔진이 dynamicUniverse=true 모드로 스킵.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpacEventDrivenStrategy implements QuantStrategy {

    private static final BigDecimal IPO_PRICE = new BigDecimal("2000");
    private static final BigDecimal BAND_LOW_RATIO = new BigDecimal("0.995");
    private static final BigDecimal BAND_HIGH_RATIO = new BigDecimal("1.03");
    private static final int EXIT_LOOKBACK_DAYS = 5;
    private static final int DEFAULT_MAX_POSITIONS = 10;

    private final SpacUniverseService universeService;
    private final SpacEventService eventService;

    @Override
    public QuantStrategyType type() { return QuantStrategyType.SPAC_EVENT_DRIVEN; }

    @Override
    public String displayName() { return "SPAC 이벤트 드리븐"; }

    @Override
    public String description() {
        return "KRX 상장 SPAC 중 가격이 공모가(₩2,000) ±3% 밴드 내이고 최근 5거래일 내 "
                + "합병 관련 공시가 없는 종목을 균등 매수 (기본 최대 10종목). "
                + "합병 공시·밴드 이탈 시 청산. 일일 리밸런싱.";
    }

    @Override
    public boolean dynamicUniverse() { return true; }

    @Override
    public List<QuantAsset> defaultUniverse() {
        // SPAC 유니버스는 DART corp-code 매핑에 의존한다 (DART_OPEN_API_KEY 필요).
        // 키 미설정/네트워크 장애 시 메타데이터 응답까지 막히면 안 되므로,
        // 빈 universe 로 폴백하고 운영자에게 WARN 으로 알린다.
        // 실제 시그널 생성/주문 단계에 universe 가 비면 그쪽에서 별도 검증/거절.
        try {
            return universeService.listSpacs().stream()
                    .map(s -> new QuantAsset(s.stockCode(), s.name(),
                            AssetType.KR_STOCK, QuantAsset.AssetRole.OFFENSIVE, null))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("SPAC universe unavailable ({}). Returning empty universe — "
                    + "configure DART_OPEN_API_KEY to enable.", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public QuantSignal generateSignal(List<QuantAsset> universe,
                                      Map<String, Map<LocalDate, BigDecimal>> prices,
                                      LocalDate asOfDate,
                                      StrategyParams params) {
        int maxPos = params.topN(DEFAULT_MAX_POSITIONS);
        BigDecimal low = IPO_PRICE.multiply(BAND_LOW_RATIO);
        BigDecimal high = IPO_PRICE.multiply(BAND_HIGH_RATIO);

        List<QuantSignal.ScoreRow> diag = new ArrayList<>();
        List<Qualifier> qualifiers = new ArrayList<>();

        for (QuantAsset a : universe) {
            Map<LocalDate, BigDecimal> priceMap = prices.get(a.getSymbol());
            BigDecimal p = QuantIndicators.closestPriceAtOrBefore(priceMap, asOfDate);
            if (p == null) continue;

            boolean inBand = p.compareTo(low) >= 0 && p.compareTo(high) <= 0;

            Optional<LocalDate> merger = Optional.empty();
            SpacUniverseService.SpacEntry entry = universeService.find(a.getSymbol());
            if (entry != null) {
                merger = eventService.latestAnnouncementBefore(
                        entry.stockCode(), entry.corpCode(), asOfDate);
            }
            boolean recentMerger = merger
                    .filter(d -> !d.isBefore(asOfDate.minusDays(EXIT_LOOKBACK_DAYS)))
                    .isPresent();

            String tag;
            boolean selected;
            if (recentMerger) {
                tag = "합병공시 " + merger.get() + " → 청산";
                selected = false;
            } else if (!inBand) {
                tag = "밴드 이탈 @₩" + p.toPlainString();
                selected = false;
            } else {
                tag = "밴드 내 @₩" + p.toPlainString();
                selected = true;
                qualifiers.add(new Qualifier(a.getSymbol(), p));
            }
            diag.add(QuantSignal.ScoreRow.builder()
                    .symbol(a.getSymbol())
                    .label(a.getName() + " " + tag)
                    .score(p)
                    .selected(selected)
                    .build());
        }

        // 공모가 대비 편차 작은 순 우선 (밴드 하단 근처 선호)
        qualifiers.sort((a, b) ->
                a.price().subtract(IPO_PRICE).abs()
                        .compareTo(b.price().subtract(IPO_PRICE).abs()));

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        int n = Math.min(maxPos, qualifiers.size());
        if (n > 0) {
            BigDecimal w = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
            for (int i = 0; i < n; i++) {
                weights.put(qualifiers.get(i).symbol(), w);
            }
        }

        String rationale = n == 0
                ? "진입 조건 충족 SPAC 없음 → 현금 100%"
                : String.format("공모가 ±3%% 밴드 + 합병공시 없는 %d종목 균등 매수 (유니버스 %d, 최대 %d)",
                        n, universe.size(), maxPos);

        return QuantSignal.builder()
                .asOfDate(asOfDate)
                .targetWeights(weights)
                .rationale(rationale)
                .diagnostics(diag)
                .build();
    }

    @Override
    public boolean shouldRebalance(LocalDate prev, LocalDate today, List<LocalDate> availableDates) {
        return true; // 이벤트 드리븐: 매 거래일 재평가
    }

    private record Qualifier(String symbol, BigDecimal price) {}
}

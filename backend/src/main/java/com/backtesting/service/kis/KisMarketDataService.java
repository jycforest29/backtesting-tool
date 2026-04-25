package com.backtesting.service.kis;

import com.backtesting.model.AssetType;
import com.backtesting.model.InvestorTrading;
import com.backtesting.model.PricePoint;
import com.backtesting.service.StockMasterService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisMarketDataService {

    private final KisAuthService auth;
    private final KisHttpCaller http;
    @Autowired
    private StockMasterService masterService;

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public record ChartResult(String name, String currency, List<PricePoint> priceHistory, BigDecimal currentPrice) {}

    public record QuoteResult(String symbol, String name, BigDecimal price, BigDecimal previousClose,
                              BigDecimal change, BigDecimal changePercent, String currency) {}

    /** 국내 종목의 최신 PER/PBR/EPS/BPS/시총/현재가. 팩터 전략 스크리닝용. */
    public record DomesticFundamentals(
            String code, String name, BigDecimal price,
            BigDecimal per, BigDecimal pbr,
            BigDecimal eps, BigDecimal bps,
            BigDecimal marketCap  // 원(KRW)
    ) {}

    /**
     * KIS inquire-price(FHKST01010100) 응답에 포함된 재무/시장 지표 추출.
     * - per, pbr, eps, bps는 KIS 계산값
     * - 시가총액 hts_avls는 "억원" 단위 → 원으로 변환
     */
    public DomesticFundamentals getDomesticFundamentals(String code) {
        try {
            String c = MarketSymbol.of(AssetType.KR_STOCK, code).code();
            Map<String, String> params = Map.of(
                    "FID_COND_MRKT_DIV_CODE", "J",
                    "FID_INPUT_ISCD", c);
            JsonNode res = http.get(
                    auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-price",
                    params,
                    http.baseHeaders(auth.getProps(), auth.getAccessToken(), "FHKST01010100"));
            JsonNode out = res.path("output");
            BigDecimal price = num(out.path("stck_prpr"));
            BigDecimal per = num(out.path("per"));
            BigDecimal pbr = num(out.path("pbr"));
            BigDecimal eps = num(out.path("eps"));
            BigDecimal bps = num(out.path("bps"));
            // hts_avls: 억원 단위. 원으로 환산 (× 100,000,000).
            BigDecimal marketCapEok = num(out.path("hts_avls"));
            BigDecimal marketCap = marketCapEok.multiply(BigDecimal.valueOf(100_000_000L));
            String name = out.path("hts_kor_isnm").asText(c);
            return new DomesticFundamentals(c, name, price, per, pbr, eps, bps, marketCap);
        } catch (Exception e) {
            throw new RuntimeException("KIS fundamentals failed for " + code + ": " + e.getMessage(), e);
        }
    }

    // ==================== 현재가 ====================

    /** 캐시 5초. 동일 종목 연속 조회는 Redis에서 hit. 실시간성 손실 최소화. */
    @Cacheable(value = "kisQuote", key = "#sym.market().name() + ':' + #sym.code()", unless = "#result == null")
    public QuoteResult getQuote(MarketSymbol sym) {
        return sym.isDomestic() ? domesticQuote(sym) : overseasQuote(sym);
    }

    private QuoteResult domesticQuote(MarketSymbol sym) {
        try {
            Map<String, String> params = Map.of(
                    "FID_COND_MRKT_DIV_CODE", "J",
                    "FID_INPUT_ISCD", sym.code());
            JsonNode res = http.get(
                    auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-price",
                    params,
                    http.baseHeaders(auth.getProps(), auth.getAccessToken(), "FHKST01010100"));
            JsonNode out = res.path("output");
            BigDecimal price = num(out.path("stck_prpr"));
            BigDecimal prev = num(out.path("stck_sdpr"));
            BigDecimal change = num(out.path("prdy_vrss"));
            BigDecimal changePct = num(out.path("prdy_ctrt"));
            String name = out.path("hts_kor_isnm").asText(sym.code());
            return new QuoteResult(sym.code(), name, price, prev, change, changePct, "KRW");
        } catch (Exception e) {
            throw new RuntimeException("KIS domestic quote failed for " + sym.code() + ": " + e.getMessage(), e);
        }
    }

    private QuoteResult overseasQuote(MarketSymbol sym) {
        try {
            Map<String, String> params = Map.of(
                    "AUTH", "",
                    "EXCD", sym.exchange(),
                    "SYMB", sym.code());
            // 해외 시세는 실전 도메인에서만 동작 (모의는 빈 응답)
            JsonNode res = http.get(
                    auth.getProps().getBaseUrlReal() + "/uapi/overseas-price/v1/quotations/price",
                    params,
                    http.baseHeaders(auth.getProps(), auth.getRealAccessToken(), "HHDFS00000300"));
            JsonNode out = res.path("output");
            BigDecimal price = num(out.path("last"));
            BigDecimal prev = num(out.path("base"));
            BigDecimal change = price.subtract(prev);
            BigDecimal changePct = prev.compareTo(BigDecimal.ZERO) > 0
                    ? change.divide(prev, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;
            return new QuoteResult(sym.code(), sym.code(), price, prev, change, changePct, sym.currency());
        } catch (Exception e) {
            throw new RuntimeException("KIS overseas quote failed for " + sym.exchange() + ":" + sym.code()
                    + ": " + e.getMessage(), e);
        }
    }

    // ==================== 기간별 시세 (백테스트용) ====================

    /**
     * 일봉 이력 캐시 5분. 동일 종목·시작일 반복 요청을 KIS API로 보내지 않음.
     * 5분은 장중 가격 변화가 있어도 월말/분기 리밸런싱에는 의미 없는 수준.
     */
    @Cacheable(value = "kisChart",
               key = "#sym.market().name() + ':' + #sym.code() + ':' + #startDate",
               unless = "#result == null")
    public ChartResult getChartData(MarketSymbol sym, LocalDate startDate) {
        QuoteResult quote = getQuote(sym);
        List<PricePoint> history = sym.isDomestic()
                ? domesticDailyHistory(sym, startDate)
                : overseasDailyHistory(sym, startDate);
        if (history.isEmpty()) {
            throw new IllegalArgumentException("No price data for " + sym.code() + " from " + startDate);
        }
        return new ChartResult(quote.name(), sym.currency(), history, quote.price());
    }

    /** 국내 일봉: inquire-daily-itemchartprice. 1회 최대 100일 → 페이지네이션. */
    private List<PricePoint> domesticDailyHistory(MarketSymbol sym, LocalDate startDate) {
        Map<LocalDate, BigDecimal> map = new TreeMap<>();
        LocalDate cursor = LocalDate.now();
        while (!cursor.isBefore(startDate)) {
            LocalDate chunkStart = cursor.minusDays(99);
            if (chunkStart.isBefore(startDate)) chunkStart = startDate;
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("FID_COND_MRKT_DIV_CODE", "J");
                params.put("FID_INPUT_ISCD", sym.code());
                params.put("FID_INPUT_DATE_1", chunkStart.format(YMD));
                params.put("FID_INPUT_DATE_2", cursor.format(YMD));
                params.put("FID_PERIOD_DIV_CODE", "D");
                params.put("FID_ORG_ADJ_PRC", "0");
                JsonNode res = http.get(
                        auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
                        params,
                        http.baseHeaders(auth.getProps(), auth.getAccessToken(), "FHKST03010100"));
                JsonNode out2 = res.path("output2");
                if (!out2.isArray() || out2.isEmpty()) break;
                for (JsonNode row : out2) {
                    String dateStr = row.path("stck_bsop_date").asText();
                    if (dateStr.length() != 8) continue;
                    LocalDate d = LocalDate.parse(dateStr, YMD);
                    BigDecimal close = num(row.path("stck_clpr"));
                    if (close.compareTo(BigDecimal.ZERO) > 0) {
                        map.put(d, close);
                    }
                }
                cursor = chunkStart.minusDays(1);
            } catch (Exception e) {
                log.warn("Domestic history fetch failed at cursor={}: {}", cursor, e.getMessage());
                break;
            }
        }
        List<PricePoint> list = new ArrayList<>();
        map.forEach((d, c) -> list.add(new PricePoint(d, c)));
        return list;
    }

    /** 해외 일봉: dailyprice. BYMD 기준 과거 N일. 페이지네이션으로 과거로 이동. */
    private List<PricePoint> overseasDailyHistory(MarketSymbol sym, LocalDate startDate) {
        Map<LocalDate, BigDecimal> map = new TreeMap<>();
        LocalDate cursor = LocalDate.now();
        int safetyGuard = 120; // 최대 120 페이지 (~12000 거래일, 40년 방어)
        while (!cursor.isBefore(startDate) && safetyGuard-- > 0) {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("AUTH", "");
                params.put("EXCD", sym.exchange());
                params.put("SYMB", sym.code());
                params.put("GUBN", "0"); // 0=일, 1=주, 2=월
                params.put("BYMD", cursor.format(YMD));
                params.put("MODP", "1"); // 수정주가
                JsonNode res = http.get(
                        auth.getProps().getBaseUrlReal() + "/uapi/overseas-price/v1/quotations/dailyprice",
                        params,
                        http.baseHeaders(auth.getProps(), auth.getRealAccessToken(), "HHDFS76240000"));
                JsonNode out2 = res.path("output2");
                if (!out2.isArray() || out2.isEmpty()) break;

                LocalDate oldest = cursor;
                int added = 0;
                for (JsonNode row : out2) {
                    String dateStr = row.path("xymd").asText();
                    if (dateStr.length() != 8) continue;
                    LocalDate d = LocalDate.parse(dateStr, YMD);
                    if (d.isBefore(startDate)) continue;
                    BigDecimal close = num(row.path("clos"));
                    if (close.compareTo(BigDecimal.ZERO) > 0) {
                        map.put(d, close);
                        added++;
                    }
                    if (d.isBefore(oldest)) oldest = d;
                }
                if (added == 0) break;
                cursor = oldest.minusDays(1);
            } catch (Exception e) {
                log.warn("Overseas history fetch failed at cursor={}: {}", cursor, e.getMessage());
                break;
            }
        }
        List<PricePoint> list = new ArrayList<>();
        map.forEach((d, c) -> list.add(new PricePoint(d, c)));
        return list;
    }

    // ==================== 종목 검색 ====================

    public List<Map<String, String>> search(String query, AssetType market) {
        List<Map<String, String>> results = new ArrayList<>();
        if (market == null || query == null || query.isBlank()) return results;

        // 1) 큐레이션된 마스터에서 이름/코드 부분일치 검색
        String currency = market == AssetType.KR_STOCK ? "KRW"
                : market == AssetType.JP_STOCK ? "JPY" : "USD";
        Set<String> dedupe = new HashSet<>();
        for (StockMasterService.StockEntry e : masterService.search(market, query)) {
            if (!dedupe.add(e.code())) continue;
            results.add(Map.of(
                    "symbol", e.code(),
                    "name", e.name(),
                    "exchange", e.exchange(),
                    "market", market.name(),
                    "currency", currency));
        }
        if (!results.isEmpty()) return results;

        // 2) 마스터에 없으면 실제 현재가 조회로 유효성 검증 (사용자가 덜 유명한 코드 입력한 경우)
        try {
            MarketSymbol sym = MarketSymbol.of(market, query);
            MarketSymbol resolved;
            QuoteResult q;
            if (market == AssetType.US_STOCK) {
                resolved = findUsExchange(sym);
                q = overseasQuote(resolved);
            } else {
                resolved = sym;
                q = getQuote(sym);
            }
            results.add(Map.of(
                    "symbol", q.symbol(),
                    "name", q.name(),
                    "exchange", resolved.exchange(),
                    "market", market.name(),
                    "currency", q.currency()));
        } catch (Exception e) {
            log.debug("Symbol validation failed: {}", e.getMessage());
        }
        return results;
    }

    /** 미국 종목의 실제 상장 거래소(NAS/NYS/AMS) 탐색. 시세가 유효하게 나오는 첫 거래소 반환. */
    private MarketSymbol findUsExchange(MarketSymbol sym) {
        for (String ex : List.of("NAS", "NYS", "AMS")) {
            MarketSymbol candidate = sym.withExchange(ex);
            try {
                QuoteResult q = overseasQuote(candidate);
                if (q.price() != null && q.price().compareTo(BigDecimal.ZERO) > 0) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("Symbol not found on any US exchange: " + sym.code());
    }

    // ==================== 국내 분봉 (단타 스캐너용) ====================

    public record MinuteBar(String time, BigDecimal price, long volume) {}

    /**
     * 국내 당일 1분봉 최대 30개 조회.
     * TR_ID: FHKST03010200. 가장 최근 분봉이 output2[0] 위치.
     * 장 외 시간엔 빈 리스트 반환될 수 있음.
     */
    public List<MinuteBar> getRecentMinuteBars(String code) {
        try {
            String c = MarketSymbol.of(AssetType.KR_STOCK, code).code();
            // FID_INPUT_HOUR_1 = 현재 시각 HHmmss
            java.time.format.DateTimeFormatter hms = java.time.format.DateTimeFormatter.ofPattern("HHmmss");
            String now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).format(hms);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("FID_ETC_CLS_CODE", "");
            params.put("FID_COND_MRKT_DIV_CODE", "J");
            params.put("FID_INPUT_ISCD", c);
            params.put("FID_INPUT_HOUR_1", now);
            params.put("FID_PW_DATA_INCU_YN", "Y");

            JsonNode res = http.get(
                    auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice",
                    params,
                    http.baseHeaders(auth.getProps(), auth.getAccessToken(), "FHKST03010200"));
            List<MinuteBar> out = new ArrayList<>();
            JsonNode arr = res.path("output2");
            if (!arr.isArray()) return out;
            for (JsonNode row : arr) {
                String t = row.path("stck_cntg_hour").asText();
                BigDecimal px = num(row.path("stck_prpr"));
                long vol = longVal(row.path("cntg_vol"));
                out.add(new MinuteBar(t, px, vol));
            }
            return out;
        } catch (Exception e) {
            log.debug("minute bars failed {}: {}", code, e.getMessage());
            return List.of();
        }
    }

    // ==================== 거래대금 (국내) ====================

    /** 국내 종목 누적 거래대금(원). 장 마감 후엔 전일 최종값. 스캐너용. */
    public long getDomesticTurnoverWon(String code) {
        try {
            String c = MarketSymbol.of(AssetType.KR_STOCK, code).code();
            Map<String, String> params = Map.of(
                    "FID_COND_MRKT_DIV_CODE", "J",
                    "FID_INPUT_ISCD", c);
            JsonNode res = http.get(
                    auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-price",
                    params,
                    http.baseHeaders(auth.getProps(), auth.getAccessToken(), "FHKST01010100"));
            return longVal(res.path("output").path("acml_tr_pbmn"));
        } catch (Exception e) {
            log.debug("turnover fetch failed {}: {}", code, e.getMessage());
            return 0L;
        }
    }

    // ==================== 투자자별 매매동향 (국내) ====================

    public InvestorTrading getInvestorFlow(String stockCode, String stockName,
                                           LocalDate startDate, LocalDate endDate) {
        try {
            Map<String, String> params = Map.of(
                    "FID_COND_MRKT_DIV_CODE", "J",
                    "FID_INPUT_ISCD", MarketSymbol.of(AssetType.KR_STOCK, stockCode).code());
            JsonNode res = http.get(
                    auth.getProps().getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-investor",
                    params,
                    http.baseHeaders(auth.getProps(), auth.getAccessToken(), "FHKST01010900"));
            JsonNode arr = res.path("output");
            return buildInvestorTrading(arr, stockCode, stockName, startDate, endDate);
        } catch (Exception e) {
            throw new RuntimeException("KIS investor flow failed: " + e.getMessage(), e);
        }
    }

    private InvestorTrading buildInvestorTrading(JsonNode arr, String code, String name,
                                                 LocalDate start, LocalDate end) {
        List<InvestorTrading.DailyTrading> daily = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode row : arr) {
                String dateStr = row.path("stck_bsop_date").asText();
                if (dateStr.length() != 8) continue;
                LocalDate d = LocalDate.parse(dateStr, YMD);
                if (d.isBefore(start) || d.isAfter(end)) continue;

                BigDecimal close = num(row.path("stck_clpr"));
                long indNet = longVal(row.path("prsn_ntby_qty"));
                long forNet = longVal(row.path("frgn_ntby_qty"));
                long instNet = longVal(row.path("orgn_ntby_qty"));
                BigDecimal indAmt = num(row.path("prsn_ntby_tr_pbmn"));
                BigDecimal forAmt = num(row.path("frgn_ntby_tr_pbmn"));
                BigDecimal instAmt = num(row.path("orgn_ntby_tr_pbmn"));

                daily.add(InvestorTrading.DailyTrading.builder()
                        .date(d)
                        .closePrice(close)
                        .individualNet(indNet)
                        .foreignNet(forNet)
                        .institutionNet(instNet)
                        .individualNetAmt(indAmt)
                        .foreignNetAmt(forAmt)
                        .institutionNetAmt(instAmt)
                        .build());
            }
        }
        daily.sort(Comparator.comparing(InvestorTrading.DailyTrading::getDate));
        long indCum = 0, forCum = 0, instCum = 0;
        for (var row : daily) {
            indCum += row.getIndividualNet();
            forCum += row.getForeignNet();
            instCum += row.getInstitutionNet();
            row.setIndividualCumNet(indCum);
            row.setForeignCumNet(forCum);
            row.setInstitutionCumNet(instCum);
        }
        return InvestorTrading.builder()
                .stockCode(code)
                .stockName(name != null ? name : code)
                .dailyData(daily)
                .summary(summarize(daily))
                .build();
    }

    private InvestorTrading.TradingSummary summarize(List<InvestorTrading.DailyTrading> data) {
        if (data.isEmpty()) {
            return InvestorTrading.TradingSummary.builder()
                    .dominantBuyer("NONE").priceDirection("FLAT").divergenceSignal("NEUTRAL").build();
        }
        long ind = data.stream().mapToLong(InvestorTrading.DailyTrading::getIndividualNet).sum();
        long fo = data.stream().mapToLong(InvestorTrading.DailyTrading::getForeignNet).sum();
        long inst = data.stream().mapToLong(InvestorTrading.DailyTrading::getInstitutionNet).sum();

        BigDecimal indAmt = data.stream().map(InvestorTrading.DailyTrading::getIndividualNetAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal foAmt = data.stream().map(InvestorTrading.DailyTrading::getForeignNetAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal instAmt = data.stream().map(InvestorTrading.DailyTrading::getInstitutionNetAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long max = Math.max(ind, Math.max(fo, inst));
        String dominant = max == fo ? "FOREIGN" : max == inst ? "INSTITUTION" : "INDIVIDUAL";

        String dir = "FLAT";
        BigDecimal first = data.get(0).getClosePrice();
        BigDecimal last = data.get(data.size() - 1).getClosePrice();
        if (first != null && last != null && first.compareTo(BigDecimal.ZERO) > 0) {
            double delta = last.subtract(first).divide(first, 4, RoundingMode.HALF_UP).doubleValue();
            if (delta > 0.02) dir = "UP";
            else if (delta < -0.02) dir = "DOWN";
        }

        long smart = fo + inst;
        String divergence = "NEUTRAL";
        if (smart > 0 && "DOWN".equals(dir)) divergence = "BULLISH";
        else if (smart < 0 && "UP".equals(dir)) divergence = "BEARISH";
        else if (smart > 0 && "UP".equals(dir)) divergence = "BULLISH";
        else if (smart < 0 && "DOWN".equals(dir)) divergence = "BEARISH";

        return InvestorTrading.TradingSummary.builder()
                .individualTotalNet(ind).foreignTotalNet(fo).institutionTotalNet(inst)
                .individualTotalAmt(indAmt).foreignTotalAmt(foAmt).institutionTotalAmt(instAmt)
                .dominantBuyer(dominant).priceDirection(dir).divergenceSignal(divergence)
                .build();
    }

    // ==================== UTIL ====================

    private static BigDecimal num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        String s = n.asText("0").replace(",", "").trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static long longVal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0;
        String s = n.asText("0").replace(",", "").trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s.split("\\.")[0]); } catch (Exception e) { return 0; }
    }
}

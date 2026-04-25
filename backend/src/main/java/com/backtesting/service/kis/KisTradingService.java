package com.backtesting.service.kis;

import com.backtesting.config.KisProperties;
import com.backtesting.model.AssetType;
import com.backtesting.model.BalanceResult;
import com.backtesting.model.OrderRequest;
import com.backtesting.model.OrderResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisTradingService {

    private final KisAuthService auth;
    private final KisHttpCaller http;
    private final KisMarketDataService marketData;

    // ========== 주문 ==========

    /** 주문 성공 시 해당 market 잔고 캐시 무효화 — 델타 재계산 시 최신값 참조. */
    @CacheEvict(value = "kisBalance", key = "#req.market.name()", condition = "#req != null")
    public OrderResult placeOrder(OrderRequest req) {
        validate(req);
        return req.getMarket() == AssetType.KR_STOCK ? domesticOrder(req) : overseasOrder(req);
    }

    /** 주식주문(현금): 매수 TTTC0012U / 매도 TTTC0011U (모의 V접두). */
    private OrderResult domesticOrder(OrderRequest req) {
        KisProperties p = auth.getProps();
        String trId;
        if ("BUY".equalsIgnoreCase(req.getSide())) {
            trId = http.tr(p, "TTTC0012U", "VTTC0012U");
        } else {
            trId = http.tr(p, "TTTC0011U", "VTTC0011U");
        }
        String code = MarketSymbol.of(AssetType.KR_STOCK, req.getSymbol()).code();
        String ordDvsn = "MARKET".equalsIgnoreCase(req.getOrderType()) ? "01" : "00";
        String price = "MARKET".equalsIgnoreCase(req.getOrderType()) ? "0" : req.getPrice();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("CANO", p.getAccountNumber());
        body.put("ACNT_PRDT_CD", p.getAccountProduct());
        body.put("PDNO", code);
        body.put("ORD_DVSN", ordDvsn);
        body.put("ORD_QTY", String.valueOf(req.getQuantity()));
        body.put("ORD_UNPR", price);

        Map<String, String> headers = http.baseHeaders(p, auth.getAccessToken(), trId);
        headers.put("hashkey", auth.hashkey(body));

        try {
            JsonNode res = http.post(p.getBaseUrl() + "/uapi/domestic-stock/v1/trading/order-cash", body, headers);
            return buildOrderResult(res);
        } catch (Exception e) {
            throw new RuntimeException("KIS domestic order failed: " + e.getMessage(), e);
        }
    }

    /** 해외주식 주문: 거래소별 TR_ID 다름. KIS 해외는 시장가 미지원 → 현재가 기준 지정가로 변환. */
    private OrderResult overseasOrder(OrderRequest req) {
        KisProperties p = auth.getProps();
        MarketSymbol sym = resolveOverseas(req);
        String trId = overseasOrderTrId(sym, req.getSide(), p.isPaperTrading());

        String unitPrice;
        if ("MARKET".equalsIgnoreCase(req.getOrderType())) {
            BigDecimal cur = marketData.getQuote(sym).price();
            if (cur == null || cur.compareTo(BigDecimal.ZERO) <= 0) {
                return OrderResult.builder().success(false)
                        .message("해외 현재가가 0으로 조회됩니다. 장외시간일 수 있으니 지정가로 주문하세요.")
                        .build();
            }
            BigDecimal factor = "BUY".equalsIgnoreCase(req.getSide())
                    ? BigDecimal.valueOf(1.02)   // 매수: 현재가 +2% 상단 버퍼
                    : BigDecimal.valueOf(0.98);  // 매도: 현재가 -2% 하단 버퍼
            int scale = "JPY".equals(sym.currency()) ? 0 : 2;
            unitPrice = cur.multiply(factor).setScale(scale, RoundingMode.HALF_UP).toPlainString();
        } else {
            unitPrice = req.getPrice();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("CANO", p.getAccountNumber());
        body.put("ACNT_PRDT_CD", p.getAccountProduct());
        body.put("OVRS_EXCG_CD", orderExchangeCode(sym.exchange()));
        body.put("PDNO", sym.code());
        body.put("ORD_QTY", String.valueOf(req.getQuantity()));
        body.put("OVRS_ORD_UNPR", unitPrice);
        body.put("ORD_SVR_DVSN_CD", "0");
        body.put("ORD_DVSN", "00"); // KIS 해외 주문은 지정가(00)만 실용적으로 사용

        Map<String, String> headers = http.baseHeaders(p, auth.getAccessToken(), trId);
        headers.put("hashkey", auth.hashkey(body));

        try {
            JsonNode res = http.post(p.getBaseUrl() + "/uapi/overseas-stock/v1/trading/order", body, headers);
            return buildOrderResult(res);
        } catch (Exception e) {
            throw new RuntimeException("KIS overseas order failed: " + e.getMessage(), e);
        }
    }

    /**
     * 해외 주문 TR_ID. 거래소별로 다름.
     * <pre>
     * 실전:
     *   미국 매수 TTTT1002U, 매도 TTTT1006U
     *   일본 매수 TTTS0308U, 매도 TTTS0307U
     * 모의:
     *   미국 매수 VTTT1002U, 매도 VTTT1001U  (!! 매도가 실전과 코드 다름)
     *   일본 매수 VTTS0308U, 매도 VTTS0307U
     * </pre>
     */
    private String overseasOrderTrId(MarketSymbol sym, String side, boolean paper) {
        boolean buy = "BUY".equalsIgnoreCase(side);
        String exchange = sym.exchange();
        if ("TSE".equals(exchange)) {
            if (paper) return buy ? "VTTS0308U" : "VTTS0307U";
            return buy ? "TTTS0308U" : "TTTS0307U";
        }
        // 미국 (NAS/NYS/AMS)
        if (paper) return buy ? "VTTT1002U" : "VTTT1001U";
        return buy ? "TTTT1002U" : "TTTT1006U";
    }

    /**
     * KIS 해외주식은 시세/주문에서 거래소 코드 체계가 다르다.
     * 시세(EXCD, 3자리): NAS, NYS, AMS, TSE
     * 주문(OVRS_EXCG_CD, 4자리): NASD, NYSE, AMEX, TKSE
     */
    private String orderExchangeCode(String quoteExchange) {
        return switch (quoteExchange) {
            case "NAS" -> "NASD";
            case "NYS" -> "NYSE";
            case "AMS" -> "AMEX";
            case "TSE" -> "TKSE";
            default -> quoteExchange;
        };
    }

    // ========== 잔고 ==========

    /**
     * 잔고 캐시 30초. 다중 전략이 동시에 리밸런싱할 때 같은 API를 N번 치는 것 방지.
     * 주문 성공 시 @CacheEvict로 바로 무효화 (stale 데이터로 델타 계산 금지).
     */
    @Cacheable(value = "kisBalance", key = "#market.name()", unless = "#result == null")
    public BalanceResult getBalance(AssetType market) {
        return market == AssetType.KR_STOCK ? domesticBalance() : overseasBalance(market);
    }

    /** 주식잔고조회: TTTC8434R / VTTC8434R */
    private BalanceResult domesticBalance() {
        KisProperties p = auth.getProps();
        String trId = http.tr(p, "TTTC8434R", "VTTC8434R");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", p.getAccountNumber());
        params.put("ACNT_PRDT_CD", p.getAccountProduct());
        params.put("AFHR_FLPR_YN", "N");
        params.put("OFL_YN", "");
        params.put("INQR_DVSN", "02");
        params.put("UNPR_DVSN", "01");
        params.put("FUND_STTL_ICLD_YN", "N");
        params.put("FNCG_AMT_AUTO_RDPT_YN", "N");
        params.put("PRCS_DVSN", "00");
        params.put("CTX_AREA_FK100", "");
        params.put("CTX_AREA_NK100", "");
        try {
            JsonNode res = http.get(
                    p.getBaseUrl() + "/uapi/domestic-stock/v1/trading/inquire-balance",
                    params, http.baseHeaders(p, auth.getAccessToken(), trId));

            List<BalanceResult.Holding> holdings = new ArrayList<>();
            for (JsonNode row : res.path("output1")) {
                long qty = longVal(row.path("hldg_qty"));
                if (qty <= 0) continue;
                holdings.add(BalanceResult.Holding.builder()
                        .symbol(row.path("pdno").asText())
                        .name(row.path("prdt_name").asText())
                        .quantity(qty)
                        .avgPrice(num(row.path("pchs_avg_pric")))
                        .currentPrice(num(row.path("prpr")))
                        .evalAmount(num(row.path("evlu_amt")))
                        .pnl(num(row.path("evlu_pfls_amt")))
                        .pnlRate(num(row.path("evlu_pfls_rt")))
                        .build());
            }
            JsonNode summary = res.path("output2").isArray() && !res.path("output2").isEmpty()
                    ? res.path("output2").get(0) : res.path("output2");
            return BalanceResult.builder()
                    .market(AssetType.KR_STOCK)
                    .deposit(num(summary.path("dnca_tot_amt")))
                    .totalEvalAmount(num(summary.path("tot_evlu_amt")))
                    .totalPnl(num(summary.path("evlu_pfls_smtl_amt")))
                    .totalPnlRate(num(summary.path("asst_icdc_erng_rt")))
                    .holdings(holdings)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("KIS domestic balance failed: " + e.getMessage(), e);
        }
    }

    /** 해외주식 잔고: TTTS3012R / VTTS3012R */
    private BalanceResult overseasBalance(AssetType market) {
        KisProperties p = auth.getProps();
        String trId = http.tr(p, "TTTS3012R", "VTTS3012R");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", p.getAccountNumber());
        params.put("ACNT_PRDT_CD", p.getAccountProduct());
        params.put("OVRS_EXCG_CD", market == AssetType.JP_STOCK ? "TKSE" : "NASD");
        params.put("TR_CRCY_CD", market == AssetType.JP_STOCK ? "JPY" : "USD");
        params.put("CTX_AREA_FK200", "");
        params.put("CTX_AREA_NK200", "");
        try {
            JsonNode res = http.get(
                    p.getBaseUrl() + "/uapi/overseas-stock/v1/trading/inquire-balance",
                    params, http.baseHeaders(p, auth.getAccessToken(), trId));

            List<BalanceResult.Holding> holdings = new ArrayList<>();
            for (JsonNode row : res.path("output1")) {
                long qty = longVal(row.path("ovrs_cblc_qty"));
                if (qty <= 0) continue;
                holdings.add(BalanceResult.Holding.builder()
                        .symbol(row.path("ovrs_pdno").asText())
                        .name(row.path("ovrs_item_name").asText())
                        .quantity(qty)
                        .avgPrice(num(row.path("pchs_avg_pric")))
                        .currentPrice(num(row.path("now_pric2")))
                        .evalAmount(num(row.path("ovrs_stck_evlu_amt")))
                        .pnl(num(row.path("frcr_evlu_pfls_amt")))
                        .pnlRate(num(row.path("evlu_pfls_rt")))
                        .build());
            }
            JsonNode summary = res.path("output2");
            BigDecimal totalEval = holdings.stream().map(BalanceResult.Holding::getEvalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return BalanceResult.builder()
                    .market(market)
                    .deposit(num(summary.path("frcr_dncl_amt1")))
                    .totalEvalAmount(totalEval)
                    .totalPnl(num(summary.path("ovrs_tot_pfls")))
                    .totalPnlRate(num(summary.path("tot_pftrt")))
                    .holdings(holdings)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("KIS overseas balance failed: " + e.getMessage(), e);
        }
    }

    // ========== 일자별 체결 내역 조회 (국내) ==========

    /**
     * 주식일별주문체결조회 (TTTC8001R 실전 / VTTC8001R 모의).
     * from/to 포함, 체결된 주문만 반환.
     * 조회 기간 최대 3개월. 페이지네이션 미구현 — 주간 리포트는 100건 이내로 충분.
     */
    public List<Map<String, Object>> getDailyExecutions(java.time.LocalDate from, java.time.LocalDate to) {
        KisProperties p = auth.getProps();
        String trId = http.tr(p, "TTTC8001R", "VTTC8001R");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", p.getAccountNumber());
        params.put("ACNT_PRDT_CD", p.getAccountProduct());
        params.put("INQR_STRT_DT", from.format(fmt));
        params.put("INQR_END_DT", to.format(fmt));
        params.put("SLL_BUY_DVSN_CD", "00"); // 전체
        params.put("INQR_DVSN", "00");       // 역순
        params.put("PDNO", "");
        params.put("CCLD_DVSN", "01");       // 체결만
        params.put("ORD_GNO_BRNO", "");
        params.put("ODNO", "");
        params.put("INQR_DVSN_3", "00");     // 전체
        params.put("INQR_DVSN_1", "");
        params.put("CTX_AREA_FK100", "");
        params.put("CTX_AREA_NK100", "");
        try {
            JsonNode res = http.get(
                    p.getBaseUrl() + "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                    params, http.baseHeaders(p, auth.getAccessToken(), trId));
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode row : res.path("output1")) {
                out.add(Map.of(
                        "date", row.path("ord_dt").asText(),
                        "orderNo", row.path("odno").asText(),
                        "symbol", row.path("pdno").asText(),
                        "name", row.path("prdt_name").asText(),
                        "side", row.path("sll_buy_dvsn_cd").asText(),  // 01=매도 02=매수
                        "quantity", longVal(row.path("tot_ccld_qty")),
                        "avgPrice", num(row.path("avg_prvs")),
                        "amount", num(row.path("tot_ccld_amt"))
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("Daily ccld fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ========== 미체결 조회 ==========

    /** 주식정정취소가능주문조회: TTTC0084R. 모의투자 미지원 → paper면 빈 리스트. */
    public List<Map<String, Object>> getOpenOrders() {
        KisProperties p = auth.getProps();
        if (p.isPaperTrading()) {
            return List.of(); // KIS 모의투자는 이 엔드포인트 미지원
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", p.getAccountNumber());
        params.put("ACNT_PRDT_CD", p.getAccountProduct());
        params.put("CTX_AREA_FK64", "");
        params.put("CTX_AREA_NK64", "");
        params.put("INQR_DVSN_1", "0");
        params.put("INQR_DVSN_2", "0");
        try {
            JsonNode res = http.get(
                    p.getBaseUrl() + "/uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl",
                    params, http.baseHeaders(p, auth.getAccessToken(), "TTTC0084R"));
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode row : res.path("output")) {
                list.add(Map.of(
                        "orderNo", row.path("odno").asText(),
                        "symbol", row.path("pdno").asText(),
                        "name", row.path("prdt_name").asText(),
                        "side", row.path("sll_buy_dvsn_cd").asText(),
                        "quantity", longVal(row.path("ord_qty")),
                        "price", num(row.path("ord_unpr")),
                        "filled", longVal(row.path("tot_ccld_qty")),
                        "remaining", longVal(row.path("rmn_qty"))));
            }
            return list;
        } catch (Exception e) {
            log.warn("Open orders fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ========== HELPERS ==========

    private MarketSymbol resolveOverseas(OrderRequest req) {
        MarketSymbol sym = MarketSymbol.of(req.getMarket(), req.getSymbol());
        if (req.getExchange() != null && !req.getExchange().isBlank()) {
            sym = sym.withExchange(req.getExchange());
        }
        return sym;
    }

    private OrderResult buildOrderResult(JsonNode res) {
        String rtCd = res.path("rt_cd").asText();
        String msg = res.path("msg1").asText();
        if (!"0".equals(rtCd)) {
            return OrderResult.builder().success(false).rawCode(rtCd).rawMessage(msg)
                    .message("주문 실패: " + msg).build();
        }
        JsonNode out = res.path("output");
        return OrderResult.builder()
                .success(true)
                .orderNo(out.path("ODNO").asText())
                .orderTime(out.path("ORD_TMD").asText())
                .rawCode(rtCd).rawMessage(msg)
                .message("주문 접수됨")
                .build();
    }

    private void validate(OrderRequest req) {
        if (req.getMarket() == null) throw new IllegalArgumentException("market is required");
        if (req.getSymbol() == null || req.getSymbol().isBlank())
            throw new IllegalArgumentException("symbol is required");
        if (req.getSide() == null || !req.getSide().matches("(?i)BUY|SELL"))
            throw new IllegalArgumentException("side must be BUY or SELL");
        if (req.getQuantity() <= 0) throw new IllegalArgumentException("quantity must be positive");
        if ("LIMIT".equalsIgnoreCase(req.getOrderType())
                && (req.getPrice() == null || req.getPrice().isBlank())) {
            throw new IllegalArgumentException("price is required for LIMIT orders");
        }
    }

    private static BigDecimal num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        String s = n.asText("0").replace(",", "").trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static long longVal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0;
        String s = n.asText("0").replace(",", "").trim();
        try { return Long.parseLong(s.split("\\.")[0]); } catch (Exception e) { return 0; }
    }
}

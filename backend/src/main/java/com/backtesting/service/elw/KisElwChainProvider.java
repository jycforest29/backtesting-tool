package com.backtesting.service.elw;

import com.backtesting.config.ElwProperties;
import com.backtesting.config.KisProperties;
import com.backtesting.model.elw.ElwModels.ElwContract;
import com.backtesting.model.elw.ElwModels.OptionType;
import com.backtesting.service.kis.KisAuthService;
import com.backtesting.service.kis.KisHttpCaller;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KIS ELW 체인 조회 구현. URL/tr_id 는 config 주입.
 * 필드명이 환경별로 다를 수 있어 여러 후보 키로 파싱 시도.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisElwChainProvider implements ElwChainProvider {

    private final ElwProperties props;
    private final KisProperties kisProps;
    private final KisAuthService auth;
    private final KisHttpCaller http;

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public boolean isAvailable() {
        return props.isEnabled()
                && notBlank(props.getChainUrl())
                && notBlank(props.getChainTrId());
    }

    @Override
    public String label() {
        return isAvailable() ? "KIS ELW API" : "KIS ELW API (미구성)";
    }

    @Override
    public List<ElwContract> fetchChain(String underlyingCode) throws Exception {
        if (!isAvailable()) return List.of();
        String token = auth.getAccessToken();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("FID_COND_MRKT_DIV_CODE", "W");
        params.put("FID_INPUT_ISCD", underlyingCode);
        Map<String, String> headers = http.baseHeaders(kisProps, token, props.getChainTrId());
        JsonNode root = http.get(props.getChainUrl(), params, headers);

        JsonNode output = root.has("output") ? root.get("output")
                : root.has("output1") ? root.get("output1")
                : root.has("output2") ? root.get("output2") : null;
        if (output == null || !output.isArray()) {
            log.warn("KIS ELW chain: unexpected response for {} — keys={}",
                    underlyingCode, iterName(root));
            return List.of();
        }
        Instant now = Instant.now();
        List<ElwContract> out = new ArrayList<>();
        for (JsonNode n : output) {
            ElwContract c = parse(n, underlyingCode, now);
            if (c != null) out.add(c);
        }
        return out;
    }

    private ElwContract parse(JsonNode n, String underlying, Instant asOf) {
        try {
            String symbol = text(n, "mksc_shrn_iscd", "elw_shrn_iscd", "stck_shrn_iscd");
            String name = text(n, "hts_kor_isnm", "elw_kor_isnm");
            String expiry = text(n, "lstn_stcd_end_dt", "expr_dt", "mtrt_dt");
            String strikeStr = text(n, "acpr", "strk_prc");
            String priceStr = text(n, "stck_prpr", "elw_prpr");
            String typeStr = text(n, "elw_gbcd", "optn_knd", "elw_ty");
            String issuer = text(n, "isu_nm", "issur_name", "lp_nm");
            String uName = text(n, "unas_isnm", "unas_kor_isnm");
            if (symbol == null || expiry == null || strikeStr == null || priceStr == null) return null;
            OptionType type = parseType(typeStr);
            if (type == null) return null;
            LocalDate ex = LocalDate.parse(expiry.substring(0, 8), YMD);
            double strike = Double.parseDouble(strikeStr);
            double price = Double.parseDouble(priceStr);
            return new ElwContract(symbol, name, underlying, uName, type, strike, ex, price, issuer, asOf);
        } catch (Exception e) {
            log.debug("ELW row parse skip: {}", e.getMessage());
            return null;
        }
    }

    private static OptionType parseType(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.equalsIgnoreCase("C") || t.equals("01") || t.contains("콜")) return OptionType.CALL;
        if (t.equalsIgnoreCase("P") || t.equals("02") || t.contains("풋")) return OptionType.PUT;
        return null;
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String iterName(JsonNode root) {
        StringBuilder sb = new StringBuilder("[");
        var it = root.fieldNames();
        while (it.hasNext()) { sb.append(it.next()); if (it.hasNext()) sb.append(","); }
        return sb.append("]").toString();
    }
}

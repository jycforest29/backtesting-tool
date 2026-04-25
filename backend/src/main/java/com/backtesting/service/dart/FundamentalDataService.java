package com.backtesting.service.dart;

import com.backtesting.config.DartProperties;
import com.backtesting.model.quant.FundamentalData;
import com.backtesting.service.kis.KisMarketDataService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 팩터 전략용 종목 펀더멘털 데이터 제공.
 *
 * 수집:
 *   1. DART 연간 재무제표 (최신 + 전년)
 *   2. KIS 현재가 + PER/PBR/시총 (KIS 계산값)
 *
 * 캐시:
 *   - in-memory ConcurrentHashMap (stockCode → (timestamp, data))
 *   - TTL: DartProperties.financialCacheHours
 *   - 앱 재시작 시 초기화됨
 *
 * 실패 처리:
 *   - DART/KIS 중 하나라도 실패하면 null 필드 허용한 부분 데이터 반환 (전략에서 필터)
 *   - 전체 실패(기업코드 없음 등) 시 Optional.empty
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalDataService {

    private final DartClient dartClient;
    private final DartCorpCodeService corpCodeService;
    private final DartProperties dartProps;
    private final KisMarketDataService kisMarketData;

    /** 관심 재무 계정명 (DART account_nm 값). IFRS 연결재무제표 기준. */
    private static final Set<String> ACCOUNT_NAMES = Set.of(
            "매출액", "수익(매출액)",
            "매출원가",
            "매출총이익",
            "영업이익", "영업이익(손실)",
            "당기순이익", "당기순이익(손실)",
            "자산총계",
            "부채총계",
            "자본총계",
            "유동자산",
            "유동부채",
            "영업활동현금흐름", "영업활동으로 인한 현금흐름", "영업활동으로인한현금흐름"
    );

    private record CacheEntry(Instant fetchedAt, FundamentalData data) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<FundamentalData> get(String stockCode) {
        return get(stockCode, null);
    }

    /** nameOverride: 알려진 이름이 있으면 전달 (DART name 대신 사용). */
    public Optional<FundamentalData> get(String stockCode, String nameOverride) {
        String code = normalize(stockCode);
        CacheEntry ce = cache.get(code);
        if (ce != null && !isExpired(ce)) return Optional.of(ce.data);

        try {
            FundamentalData fd = fetch(code, nameOverride);
            if (fd == null) return Optional.empty();
            fd.derive();
            cache.put(code, new CacheEntry(Instant.now(), fd));
            return Optional.of(fd);
        } catch (Exception e) {
            log.warn("Fundamentals fetch failed for {}: {}", code, e.getMessage());
            return Optional.empty();
        }
    }

    /** 유니버스 일괄 조회 (부분 실패 허용). 순서 보장. */
    public List<FundamentalData> getAll(List<KospiUniverseService.Stock> universe) {
        List<FundamentalData> out = new ArrayList<>();
        for (KospiUniverseService.Stock s : universe) {
            get(s.code(), s.name()).ifPresent(out::add);
        }
        return out;
    }

    public void clearCache() {
        cache.clear();
    }

    public int cacheSize() { return cache.size(); }

    // ========== INTERNAL ==========

    private boolean isExpired(CacheEntry ce) {
        long ttlSec = (long) dartProps.getFinancialCacheHours() * 3600L;
        return Instant.now().isAfter(ce.fetchedAt.plusSeconds(ttlSec));
    }

    private FundamentalData fetch(String code, String nameOverride) {
        FundamentalData.FundamentalDataBuilder b = FundamentalData.builder()
                .stockCode(code)
                .name(nameOverride != null ? nameOverride
                        : corpCodeService.nameFor(code).orElse(code));

        // 1) KIS 현재가 + PER/PBR/시총 (DART 실패해도 이건 성공하도록 try-catch)
        try {
            KisMarketDataService.DomesticFundamentals kf = kisMarketData.getDomesticFundamentals(code);
            b.currentPrice(kf.price())
             .per(kf.per()).pbr(kf.pbr())
             .eps(kf.eps()).bps(kf.bps())
             .marketCap(kf.marketCap());
            if (nameOverride == null && kf.name() != null && !kf.name().isBlank()) {
                b.name(kf.name());
            }
        } catch (Exception e) {
            log.warn("KIS fundamentals unavailable for {}: {}", code, e.getMessage());
        }

        // 2) DART 재무제표
        if (dartClient.isConfigured()) {
            populateDartFinancials(code, b);
        }

        return b.build();
    }

    private void populateDartFinancials(String stockCode, FundamentalData.FundamentalDataBuilder b) {
        Optional<String> corpCodeOpt = corpCodeService.corpCodeFor(stockCode);
        if (corpCodeOpt.isEmpty()) {
            log.debug("DART corp code not found for stock {}", stockCode);
            return;
        }
        String corpCode = corpCodeOpt.get();

        // 연도 선택: 전년도 사업보고서 우선 (4~12월 집계). 없으면 2년 전.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int[] candidateYears = { today.getYear() - 1, today.getYear() - 2 };
        JsonNode list = null;
        int usedYear = -1;
        for (int y : candidateYears) {
            try {
                JsonNode res = dartClient.getJson("/fnlttSinglAcntAll.json", Map.of(
                        "corp_code", corpCode,
                        "bsns_year", String.valueOf(y),
                        "reprt_code", dartProps.getDefaultReportCode(),
                        "fs_div", "CFS"
                ));
                String status = res.path("status").asText();
                if ("000".equals(status)) {
                    list = res.path("list");
                    usedYear = y;
                    break;
                }
                // 연결재무제표 없는 경우(소규모) 개별재무제표(OFS) 재시도
                res = dartClient.getJson("/fnlttSinglAcntAll.json", Map.of(
                        "corp_code", corpCode,
                        "bsns_year", String.valueOf(y),
                        "reprt_code", dartProps.getDefaultReportCode(),
                        "fs_div", "OFS"
                ));
                if ("000".equals(res.path("status").asText())) {
                    list = res.path("list");
                    usedYear = y;
                    break;
                }
            } catch (Exception e) {
                log.debug("DART fetch failed for {} year {}: {}", stockCode, y, e.getMessage());
            }
        }
        if (list == null || !list.isArray() || list.isEmpty()) {
            log.debug("DART returned no financials for stock {}", stockCode);
            return;
        }

        // 계정명 → (당기, 전기) 값 맵 구성. 손익계산서는 BS와 별도.
        Map<String, BigDecimal[]> accounts = new LinkedHashMap<>();
        for (JsonNode row : list) {
            String nm = row.path("account_nm").asText();
            if (nm == null || nm.isBlank()) continue;
            if (!matchesKnownAccount(nm)) continue;
            BigDecimal th = parseAmount(row.path("thstrm_amount").asText());
            BigDecimal fr = parseAmount(row.path("frmtrm_amount").asText());
            // 같은 계정명 중복(연결/별도 사업부문 내역) 있으면 절대값 큰 쪽 우선
            BigDecimal[] cur = accounts.get(nm);
            if (cur == null || (th != null && (cur[0] == null || th.abs().compareTo(cur[0].abs()) > 0))) {
                accounts.put(nm, new BigDecimal[] { th, fr });
            }
        }

        BigDecimal[] rev = first(accounts, "매출액", "수익(매출액)");
        BigDecimal[] gp  = first(accounts, "매출총이익");
        BigDecimal[] op  = first(accounts, "영업이익", "영업이익(손실)");
        BigDecimal[] ni  = first(accounts, "당기순이익", "당기순이익(손실)");
        BigDecimal[] ta  = first(accounts, "자산총계");
        BigDecimal[] tl  = first(accounts, "부채총계");
        BigDecimal[] te  = first(accounts, "자본총계");
        BigDecimal[] ca  = first(accounts, "유동자산");
        BigDecimal[] cl  = first(accounts, "유동부채");
        BigDecimal[] ocf = first(accounts, "영업활동현금흐름", "영업활동으로 인한 현금흐름", "영업활동으로인한현금흐름");

        if (rev != null) { b.revenue(rev[0]); b.revenuePrev(rev[1]); }
        if (gp != null)  { b.grossProfit(gp[0]); b.grossProfitPrev(gp[1]); }
        if (op != null)  { b.operatingIncome(op[0]); }
        if (ni != null)  { b.netIncome(ni[0]); b.netIncomePrev(ni[1]); }
        if (ta != null)  { b.totalAssets(ta[0]); b.totalAssetsPrev(ta[1]); }
        if (tl != null)  { b.totalLiabilities(tl[0]); b.totalLiabilitiesPrev(tl[1]); }
        if (te != null)  { b.totalEquity(te[0]); }
        if (ca != null)  { b.currentAssets(ca[0]); b.currentAssetsPrev(ca[1]); }
        if (cl != null)  { b.currentLiabilities(cl[0]); b.currentLiabilitiesPrev(cl[1]); }
        if (ocf != null) { b.operatingCashFlow(ocf[0]); }

        // 매출총이익이 없는 경우(일부 업종 계정 구조 다름) 파생: 매출 - 매출원가
        if (gp == null && rev != null) {
            BigDecimal[] cogs = first(accounts, "매출원가");
            if (cogs != null && rev[0] != null && cogs[0] != null) {
                b.grossProfit(rev[0].subtract(cogs[0]));
                if (rev[1] != null && cogs[1] != null) b.grossProfitPrev(rev[1].subtract(cogs[1]));
            }
        }
    }

    private static boolean matchesKnownAccount(String nm) {
        for (String k : ACCOUNT_NAMES) if (k.equals(nm)) return true;
        return false;
    }

    @SafeVarargs
    private static BigDecimal[] first(Map<String, BigDecimal[]> accs, String... keys) {
        for (String k : keys) {
            BigDecimal[] v = accs.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) return null;
        String s = raw.replace(",", "").trim();
        // 음수는 "(1234)" 형식이 아니라 "-1234" 형식으로 옴 (DART 표준)
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private static String normalize(String code) {
        if (code == null) return "";
        String s = code.trim();
        if (s.length() > 6) s = s.substring(s.length() - 6);
        if (s.length() < 6) s = String.format("%06d", Integer.parseInt(s));
        return s;
    }
}

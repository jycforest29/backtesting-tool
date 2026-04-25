package com.backtesting.service;

import com.backtesting.config.AlertProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * DART 공시 아침 브리핑.
 * 평일 08:10 KST — 전 거래일 장 마감 후 ~ 오늘 아침 사이 접수된 상장사 공시 중
 * 호재/악재로 분류 가능한 것만 초보자용 설명과 함께 메일로 요약 발송.
 *
 * 분류는 제목 키워드 기반 단순 휴리스틱. 금액·내용 파싱은 Phase 2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DartDisclosureService {

    private final AlertProperties alertProps;
    private final EmailService emailService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public enum Sentiment { POSITIVE, NEGATIVE }

    /** 공시 제목 키워드 → (호재/악재, 한 줄 설명). 먼저 매칭되는 규칙 우선. */
    private record Rule(String keyword, Sentiment sentiment, String plainExplanation) {}

    private static final List<Rule> RULES = List.of(
            // ── 호재 ──
            new Rule("자기주식취득", Sentiment.POSITIVE,
                    "회사가 자기 주식을 사들여요. 주당 가치가 올라가는 효과 — 보통 주가에 긍정적."),
            new Rule("자사주취득", Sentiment.POSITIVE,
                    "자사주 매입. 주당 가치 상승 효과 — 보통 긍정적."),
            new Rule("자기주식 취득 결과", Sentiment.POSITIVE,
                    "자사주 매입을 완료했다는 보고. 긍정적."),
            new Rule("자기주식 소각", Sentiment.POSITIVE,
                    "매입한 자사주를 없애버려요. 발행주식수가 줄어들어 주당 가치 상승 — 강한 호재."),
            new Rule("무상증자", Sentiment.POSITIVE,
                    "주주에게 공짜 주식을 더 줘요. 심리적 호재로 주가가 오르는 경향."),
            new Rule("단일판매ㆍ공급계약", Sentiment.POSITIVE,
                    "큰 공급계약을 따냈어요. 매출 증가 기대 — 긍정적."),
            new Rule("단일판매·공급계약", Sentiment.POSITIVE,
                    "큰 공급계약을 따냈어요. 매출 증가 기대 — 긍정적."),
            new Rule("공급계약", Sentiment.POSITIVE,
                    "신규 공급계약. 매출 증가 기대."),
            new Rule("현금ㆍ현물배당 결정", Sentiment.POSITIVE,
                    "주주 배당을 결정. 현금 받는 주주에게 긍정적."),
            new Rule("현금·현물배당", Sentiment.POSITIVE,
                    "주주 배당. 긍정적."),

            // ── 악재 ──
            new Rule("유상증자", Sentiment.NEGATIVE,
                    "돈 받고 새 주식을 발행해요. 기존 주주 지분이 희석돼 보통 악재."),
            new Rule("전환사채권", Sentiment.NEGATIVE,
                    "나중에 주식으로 바뀔 수 있는 채권(CB) 발행. 잠재적 지분 희석 우려."),
            new Rule("전환사채", Sentiment.NEGATIVE,
                    "CB 발행. 잠재적 희석."),
            new Rule("신주인수권부사채", Sentiment.NEGATIVE,
                    "BW 발행. 잠재적 희석."),
            new Rule("교환사채", Sentiment.NEGATIVE,
                    "EB 발행. 주식 교환권 있어 잠재적 희석."),
            new Rule("자기주식처분", Sentiment.NEGATIVE,
                    "회사가 보유 자사주를 시장에 팔아요. 매도 물량 풀려 단기 악재."),
            new Rule("자기주식 처분", Sentiment.NEGATIVE,
                    "자사주 매도. 시장 물량 증가 — 단기 악재."),
            new Rule("무상감자", Sentiment.NEGATIVE,
                    "자본을 줄여요. 재무 문제 신호 — 보통 악재."),
            new Rule("유상감자", Sentiment.NEGATIVE,
                    "자본을 돌려주며 줄여요. 성장 정체 신호로 해석되기 쉬움."),
            new Rule("관리종목", Sentiment.NEGATIVE,
                    "거래소가 문제 있다고 경고. 상장폐지 가능성도 — 위험."),
            new Rule("투자경고종목", Sentiment.NEGATIVE,
                    "이상 급등 경고. 추가 하락 리스크 큼."),
            new Rule("투자위험종목", Sentiment.NEGATIVE,
                    "거래소의 강한 경고. 매수 매우 주의."),
            new Rule("투자주의종목", Sentiment.NEGATIVE,
                    "거래소 주의 경고. 변동성 주의."),
            new Rule("매매거래정지", Sentiment.NEGATIVE,
                    "거래정지. 심각한 사유가 있다는 뜻 — 위험."),
            new Rule("상장폐지", Sentiment.NEGATIVE,
                    "상장폐지 이슈. 원금 손실 가능성 매우 큼."),
            new Rule("횡령", Sentiment.NEGATIVE,
                    "경영진 비리 혐의. 큰 악재."),
            new Rule("배임", Sentiment.NEGATIVE,
                    "경영진 비리 혐의. 큰 악재."),
            new Rule("부도", Sentiment.NEGATIVE,
                    "부도. 상장폐지 고위험."),
            new Rule("회생절차", Sentiment.NEGATIVE,
                    "법정관리 신청. 매우 큰 악재."),
            new Rule("소송", Sentiment.NEGATIVE,
                    "소송 제기. 건에 따라 다르지만 일반적으로 단기 악재.")
    );

    private record Classified(JsonNode raw, Rule rule) {}

    // ========== 스케줄 ==========

    /** 평일 08:10 KST — 장 시작 전 브리핑. */
    @Scheduled(cron = "0 10 8 ? * MON-FRI", zone = "Asia/Seoul")
    public void morningBriefing() {
        log.info("DART morning briefing start");
        brief();
    }

    /** 수동 트리거. */
    public void briefNow() {
        new Thread(this::brief, "dart-brief-manual").start();
    }

    // ========== 본체 ==========

    private void brief() {
        var dart = alertProps.getDart();
        if (dart.getApiKey() == null || dart.getApiKey().isBlank()) {
            log.info("DART api key not set — brief skipped");
            return;
        }
        try {
            LocalDate today = LocalDate.now(SEOUL);
            LocalDate yesterday = today.minusDays(1);
            // 어제 + 오늘 접수분을 모두 조회 후 "어제 15:30 ~ 오늘 08:10" 범위로 시간 필터링
            List<JsonNode> rows = new ArrayList<>();
            rows.addAll(fetchPage(dart.getApiKey(), yesterday, today));
            // 키워드 매칭 + 필터링
            List<Classified> classified = new ArrayList<>();
            for (JsonNode row : rows) {
                String stockCode = row.path("stock_code").asText();
                if (stockCode.isBlank()) continue; // 상장사만
                String title = row.path("report_nm").asText();
                Rule matched = classify(title);
                if (matched == null) continue;
                classified.add(new Classified(row, matched));
            }

            if (classified.isEmpty()) {
                log.info("DART brief: no classifiable disclosures");
                emailService.sendHtml(
                        "[공시 브리핑] " + today + " — 특이사항 없음",
                        "<div style='font-family:sans-serif'>"
                                + "<p>어제 장 마감 후 ~ 오늘 오전 사이 호재/악재로 분류할 공시가 없습니다.</p>"
                                + "<p style='color:#6B7280;font-size:12px'>※ 제목 키워드 기반 단순 분류. 전체 공시는 DART에서 직접 확인 권장.</p>"
                                + "</div>");
                return;
            }

            // 최대 N개 제한 + 호재/악재 분리
            int max = alertProps.getDart().getMaxItems();
            List<Classified> pos = classified.stream()
                    .filter(c -> c.rule.sentiment == Sentiment.POSITIVE).toList();
            List<Classified> neg = classified.stream()
                    .filter(c -> c.rule.sentiment == Sentiment.NEGATIVE).toList();
            if (pos.size() + neg.size() > max) {
                int half = max / 2;
                pos = pos.subList(0, Math.min(pos.size(), half));
                neg = neg.subList(0, Math.min(neg.size(), max - pos.size()));
            }

            String html = renderBrief(today, pos, neg, classified.size());
            emailService.sendHtml(
                    String.format("[공시 브리핑] %s — 호재 %d · 악재 %d",
                            today, pos.size(), neg.size()),
                    html);
            log.info("DART brief sent: pos={}, neg={}, total classifiable={}",
                    pos.size(), neg.size(), classified.size());
        } catch (Exception e) {
            log.error("DART brief failed: {}", e.getMessage(), e);
        }
    }

    private List<JsonNode> fetchPage(String key, LocalDate from, LocalDate to) {
        try {
            String url = "https://opendart.fss.or.kr/api/list.json"
                    + "?crtfc_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "&bgn_de=" + from.format(FMT)
                    + "&end_de=" + to.format(FMT)
                    + "&page_count=100";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("DART HTTP {}: {}", res.statusCode(), truncate(res.body()));
                return List.of();
            }
            JsonNode json = mapper.readTree(res.body());
            String status = json.path("status").asText();
            if (!"000".equals(status) && !"013".equals(status)) {
                log.warn("DART status={}: {}", status, json.path("message").asText());
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            JsonNode list = json.path("list");
            if (list.isArray()) list.forEach(out::add);
            return out;
        } catch (Exception e) {
            log.warn("DART fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Rule classify(String title) {
        if (title == null) return null;
        for (Rule r : RULES) {
            if (title.contains(r.keyword)) return r;
        }
        return null;
    }

    private String renderBrief(LocalDate today, List<Classified> pos,
                               List<Classified> neg, int totalClassifiable) {
        StringBuilder sb = new StringBuilder("<div style='font-family:sans-serif;max-width:720px'>");
        sb.append("<h2>공시 브리핑 — ").append(today).append("</h2>");
        sb.append("<p style='color:#6B7280'>전 거래일 장 마감 후 ~ 오늘 아침 사이 접수된 상장사 공시 중 "
                + "호재/악재로 분류된 ").append(totalClassifiable).append("건.</p>");

        if (!pos.isEmpty()) {
            sb.append("<h3 style='color:#059669'>🟢 호재 (").append(pos.size()).append(")</h3>");
            for (Classified c : pos) appendCard(sb, c, "#D1FAE5", "#065F46");
        }
        if (!neg.isEmpty()) {
            sb.append("<h3 style='color:#B91C1C'>🔴 악재 (").append(neg.size()).append(")</h3>");
            for (Classified c : neg) appendCard(sb, c, "#FEE2E2", "#991B1B");
        }

        sb.append("<hr><p style='color:#aaa;font-size:11px'>"
                + "※ 제목 키워드 기반 자동 분류. 실제 영향은 공시 내용·규모에 따라 다릅니다. "
                + "매수/매도 판단 전에 DART 원문 확인 권장.</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private void appendCard(StringBuilder sb, Classified c, String bg, String color) {
        String corp = c.raw.path("corp_name").asText();
        String stockCode = c.raw.path("stock_code").asText();
        String title = c.raw.path("report_nm").asText();
        String rceptNo = c.raw.path("rcept_no").asText();
        String rceptDt = c.raw.path("rcept_dt").asText();
        String viewUrl = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo;

        sb.append("<div style='background:").append(bg)
                .append(";border-left:4px solid ").append(color)
                .append(";padding:12px 16px;margin-bottom:10px;border-radius:6px'>")
                .append("<div style='font-size:15px;color:#111'><b>").append(corp).append("</b>")
                .append(!stockCode.isBlank() ? " <span style='color:#6B7280'>(" + stockCode + ")</span>" : "")
                .append("</div>")
                .append("<div style='font-size:13px;margin-top:4px'>").append(title).append("</div>")
                .append("<div style='font-size:12px;color:#4B5563;margin-top:6px'>💡 ")
                .append(c.rule.plainExplanation).append("</div>")
                .append("<div style='font-size:11px;color:#9CA3AF;margin-top:8px'>접수일 ")
                .append(rceptDt).append(" · <a href='").append(viewUrl).append("' style='color:")
                .append(color).append("'>원문 보기 →</a></div>")
                .append("</div>");
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}

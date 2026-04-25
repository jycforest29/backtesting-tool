package com.backtesting.service;

import com.backtesting.service.kis.KisTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 매주 월요일 08:30 KST 지난 7일 매매 리포트 발송.
 * KIS 일별체결 내역을 원천 데이터로 사용 (서버 재시작 무관).
 * 종목별 매수 평균 vs 매도 평균으로 근사 실현 PnL 계산 (정확한 LIFO/FIFO 매칭은 Phase 2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReportJob {

    private final KisTradingService kisTrading;
    private final EmailService emailService;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DFMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Scheduled(cron = "0 30 8 ? * MON", zone = "Asia/Seoul")
    public void run() {
        log.info("Weekly report scheduled run start");
        try {
            String html = buildReport();
            LocalDate today = LocalDate.now(SEOUL);
            emailService.sendHtml("[주간 리포트] " + today.minusDays(7).format(DFMT)
                    + " ~ " + today.minusDays(1).format(DFMT), html);
        } catch (Exception e) {
            log.error("Weekly report failed: {}", e.getMessage(), e);
        }
    }

    /** 수동 실행용. */
    public void runNow() {
        new Thread(() -> {
            try {
                String html = buildReport();
                LocalDate today = LocalDate.now(SEOUL);
                emailService.sendHtml("[주간 리포트 · 수동] " + today.minusDays(7).format(DFMT)
                        + " ~ " + today.minusDays(1).format(DFMT), html);
            } catch (Exception e) {
                log.error("Manual weekly report failed: {}", e.getMessage(), e);
            }
        }, "weekly-report-manual").start();
    }

    private String buildReport() {
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate from = today.minusDays(7);
        LocalDate to = today.minusDays(1);

        List<Map<String, Object>> execs = kisTrading.getDailyExecutions(from, to);
        if (execs.isEmpty()) {
            return "<div style='font-family:sans-serif'><h3>주간 리포트</h3>"
                    + "<p>" + from.format(DFMT) + " ~ " + to.format(DFMT) + " 기간 체결 내역이 없습니다.</p></div>";
        }

        // 집계
        int buys = 0, sells = 0;
        BigDecimal totalBuyAmt = BigDecimal.ZERO, totalSellAmt = BigDecimal.ZERO;
        // 종목별 buy/sell 평균가·수량 누적
        Map<String, SymStats> bySymbol = new HashMap<>();

        for (var row : execs) {
            String side = String.valueOf(row.get("side"));
            boolean buy = "02".equals(side);
            long qty = (long) row.get("quantity");
            BigDecimal amt = (BigDecimal) row.get("amount");
            String sym = (String) row.get("symbol");
            String name = (String) row.get("name");
            SymStats s = bySymbol.computeIfAbsent(sym, k -> new SymStats(sym, name));
            if (buy) {
                buys++;
                totalBuyAmt = totalBuyAmt.add(amt);
                s.buyQty += qty;
                s.buyAmt = s.buyAmt.add(amt);
            } else {
                sells++;
                totalSellAmt = totalSellAmt.add(amt);
                s.sellQty += qty;
                s.sellAmt = s.sellAmt.add(amt);
            }
        }

        // 종목별 근사 실현 손익 = (매도 평균가 - 매수 평균가) × min(매수수량, 매도수량)
        List<SymStats> stats = new ArrayList<>(bySymbol.values());
        for (SymStats s : stats) s.computePnl();
        stats.sort(Comparator.comparing((SymStats a) -> a.realizedPnl).reversed());

        BigDecimal totalRealized = stats.stream()
                .map(s -> s.realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long winCount = stats.stream().filter(s -> s.realizedPnl.compareTo(BigDecimal.ZERO) > 0).count();
        long lossCount = stats.stream().filter(s -> s.realizedPnl.compareTo(BigDecimal.ZERO) < 0).count();
        long flatCount = stats.size() - winCount - lossCount;
        double winRate = (winCount + lossCount) == 0 ? 0 : (double) winCount / (winCount + lossCount) * 100;

        BigDecimal avgWin = avg(stats.stream().filter(s -> s.realizedPnl.compareTo(BigDecimal.ZERO) > 0)
                .map(s -> s.realizedPnl).toList());
        BigDecimal avgLoss = avg(stats.stream().filter(s -> s.realizedPnl.compareTo(BigDecimal.ZERO) < 0)
                .map(s -> s.realizedPnl.abs()).toList());
        double payoffRatio = (avgLoss.compareTo(BigDecimal.ZERO) > 0)
                ? avgWin.divide(avgLoss, 2, RoundingMode.HALF_UP).doubleValue() : 0;

        // 5배 목표 진척 — 사용자 베이스 100만, 목표 500만 가정
        long baseCapital = 1_000_000L;
        long targetCapital = 5_000_000L;

        StringBuilder sb = new StringBuilder("<div style='font-family:sans-serif;max-width:720px'>");
        sb.append("<h2>주간 매매 리포트</h2>");
        sb.append("<p style='color:#6B7280'>기간: ").append(from.format(DFMT)).append(" ~ ").append(to.format(DFMT)).append("</p>");

        sb.append("<h3>요약</h3><table style='border-collapse:collapse'>");
        sb.append(kv("체결 건수", String.format("매수 %d · 매도 %d (총 %d)", buys, sells, buys + sells)));
        sb.append(kv("매수 대금 합", fmtKrw(totalBuyAmt)));
        sb.append(kv("매도 대금 합", fmtKrw(totalSellAmt)));
        sb.append(kv("근사 실현 손익", colorPnl(totalRealized)));
        sb.append(kv("종목 수", stats.size() + " (승 " + winCount + " · 패 " + lossCount + " · 미청산/무손익 " + flatCount + ")"));
        sb.append(kv("승률", String.format("%.1f%%", winRate)));
        sb.append(kv("평균 수익", fmtKrw(avgWin)));
        sb.append(kv("평균 손실", fmtKrw(avgLoss)));
        sb.append(kv("손익비", payoffRatio == 0 ? "-" : String.format("1 : %.2f", payoffRatio)));
        sb.append("</table>");

        sb.append("<h3>종목별 성과</h3>");
        sb.append("<table style='border-collapse:collapse;width:100%'>");
        sb.append("<thead><tr style='background:#f3f4f6'>")
                .append(th("종목")).append(th("매수")).append(th("매도"))
                .append(th("매수 평단")).append(th("매도 평단")).append(th("근사 PnL"))
                .append("</tr></thead><tbody>");
        for (SymStats s : stats) {
            sb.append("<tr style='border-bottom:1px solid #E5E7EB'>")
                    .append(td("<b>").append(s.name).append("</b><br><span style='color:#999;font-size:12px'>")
                            .append(s.code).append("</span>")).append("</td>")
                    .append(td(s.buyQty + "주 / " + fmtKrw(s.buyAmt))).append("</td>")
                    .append(td(s.sellQty + "주 / " + fmtKrw(s.sellAmt))).append("</td>")
                    .append(td(s.buyQty > 0 ? fmtKrw(s.buyAmt.divide(BigDecimal.valueOf(s.buyQty), 0, RoundingMode.HALF_UP)) : "-")).append("</td>")
                    .append(td(s.sellQty > 0 ? fmtKrw(s.sellAmt.divide(BigDecimal.valueOf(s.sellQty), 0, RoundingMode.HALF_UP)) : "-")).append("</td>")
                    .append(td(colorPnl(s.realizedPnl))).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");

        // 5배 프로젝트 진행 상황 (간단 추정)
        sb.append("<h3>5배 목표 진척 (대략)</h3>");
        sb.append("<p>베이스 ").append(fmtKrw(BigDecimal.valueOf(baseCapital)))
                .append(" → 목표 ").append(fmtKrw(BigDecimal.valueOf(targetCapital))).append("</p>");
        sb.append("<p>이번 주 실현 기여: <b>").append(colorPnl(totalRealized)).append("</b></p>");
        sb.append("<p style='color:#6B7280;font-size:12px'>※ 정확한 자본 곡선은 연동된 KIS 잔고 기준. 본 리포트는 체결 내역 기반 근사치.</p>");

        sb.append("<hr><p style='color:#aaa;font-size:11px'>자동 생성 · 투자 조언 아님</p>");
        sb.append("</div>");
        return sb.toString();
    }

    // helpers

    private static class SymStats {
        String code, name;
        long buyQty, sellQty;
        BigDecimal buyAmt = BigDecimal.ZERO, sellAmt = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;

        SymStats(String code, String name) { this.code = code; this.name = name; }

        void computePnl() {
            if (buyQty > 0 && sellQty > 0) {
                BigDecimal buyAvg = buyAmt.divide(BigDecimal.valueOf(buyQty), 4, RoundingMode.HALF_UP);
                BigDecimal sellAvg = sellAmt.divide(BigDecimal.valueOf(sellQty), 4, RoundingMode.HALF_UP);
                long matched = Math.min(buyQty, sellQty);
                realizedPnl = sellAvg.subtract(buyAvg).multiply(BigDecimal.valueOf(matched))
                        .setScale(0, RoundingMode.HALF_UP);
            }
        }
    }

    private static BigDecimal avg(List<BigDecimal> xs) {
        if (xs.isEmpty()) return BigDecimal.ZERO;
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(xs.size()), 0, RoundingMode.HALF_UP);
    }

    private static String kv(String k, String v) {
        return "<tr><td style='padding:4px 14px 4px 0;color:#666'>" + k + "</td>"
                + "<td style='padding:4px 0'><b>" + v + "</b></td></tr>";
    }

    private static String th(String s) {
        return "<th style='padding:6px 8px;border-bottom:2px solid #E5E7EB;text-align:left;font-size:13px'>" + s + "</th>";
    }

    private static StringBuilder td(String content) {
        return new StringBuilder("<td style='padding:6px 8px;font-size:13px'>").append(content);
    }

    private static String colorPnl(BigDecimal pnl) {
        if (pnl == null) return "-";
        int sign = pnl.compareTo(BigDecimal.ZERO);
        String color = sign > 0 ? "#1b7d1b" : sign < 0 ? "#c0392b" : "#6B7280";
        return "<span style='color:" + color + ";font-weight:600'>" + fmtKrw(pnl) + "</span>";
    }

    private static String fmtKrw(BigDecimal v) {
        if (v == null) return "0원";
        return String.format("%+,d원", v.longValue());
    }
}

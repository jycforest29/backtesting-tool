package com.backtesting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/** 평일 아침 8시 (KST) 수급 리포트 발송. 토·일은 @Scheduled 의 cron 에서 제외. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySupplyDemandReportJob {

    private final SupplyDemandScannerService scanner;
    private final EmailService emailService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 월~금 08:00 KST (application.yml timezone이 기본 system → 서버가 KST여야 함)
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        log.info("Daily supply-demand scan start");
        try {
            var report = scanner.scan();
            String html = buildHtml(report);
            String subject = String.format("[수급 리포트] %s 장 외국인/기관 Top%d",
                    report.getTradingDate().format(DATE_FMT),
                    Math.max(report.getTopBuy().size(), report.getTopSell().size()));
            emailService.sendHtml(subject, html);
        } catch (Exception e) {
            log.error("Daily report failed: {}", e.getMessage(), e);
        }
    }

    /** 수동 실행용 (테스트/트리거 API). 백그라운드 스레드에서 실행 — HTTP는 즉시 리턴. */
    public void runNow() {
        new Thread(this::run, "supply-demand-report-manual").start();
    }

    private String buildHtml(SupplyDemandScannerService.ScanReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:sans-serif;max-width:720px'>");
        sb.append("<h2>외국인+기관 수급 리포트 (").append(report.getTradingDate().format(DATE_FMT)).append(")</h2>");
        sb.append("<p style='color:#666'>스캔 대상: ").append(report.getCandidatesScanned()).append("종목 | ")
                .append("기준 2/3 이상 통과 종목만 노출</p>");

        appendSection(sb, "🟢 외국인+기관 순매수 Top", report.getTopBuy(), true);
        appendSection(sb, "🔴 외국인+기관 순매도 Top", report.getTopSell(), false);

        if (report.getTopBuy().isEmpty() && report.getTopSell().isEmpty()) {
            sb.append("<p style='color:#999'>기준 통과 종목이 없습니다.</p>");
        }

        sb.append("<hr><p style='color:#aaa;font-size:12px'>자동 생성 · 투자 조언 아님</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String title,
                               List<SupplyDemandScannerService.ScanRow> rows, boolean buySide) {
        sb.append("<h3>").append(title).append("</h3>");
        if (rows.isEmpty()) {
            sb.append("<p style='color:#999'>해당 없음</p>");
            return;
        }
        sb.append("<table style='border-collapse:collapse;width:100%'>");
        sb.append("<thead><tr style='background:#f2f2f2'>")
                .append("<th style='padding:6px;border:1px solid #ddd;text-align:left'>종목</th>")
                .append("<th style='padding:6px;border:1px solid #ddd'>외+기 순매수</th>")
                .append("<th style='padding:6px;border:1px solid #ddd'>개인 순매수</th>")
                .append("<th style='padding:6px;border:1px solid #ddd'>전일 외+기</th>")
                .append("<th style='padding:6px;border:1px solid #ddd'>거래대금</th>")
                .append("<th style='padding:6px;border:1px solid #ddd'>통과 기준</th>")
                .append("</tr></thead><tbody>");
        for (var r : rows) {
            String badges = (r.isCriteriaRetailDivergence() ? "개인역방향 " : "")
                    + (r.isCriteriaPrevDayAmplification() ? "전일증폭 " : "")
                    + (r.isCriteriaTurnover() ? "거래대금 " : "");
            sb.append("<tr>")
                    .append("<td style='padding:6px;border:1px solid #ddd'><b>")
                    .append(r.getName()).append("</b><br><span style='color:#999'>")
                    .append(r.getCode()).append("</span></td>")
                    .append("<td style='padding:6px;border:1px solid #ddd;text-align:right;color:")
                    .append(buySide ? "#1b7d1b" : "#c0392b").append("'>")
                    .append(formatBillion(r.getForeignInstNet())).append("억</td>")
                    .append("<td style='padding:6px;border:1px solid #ddd;text-align:right'>")
                    .append(formatBillion(r.getRetailNet())).append("억</td>")
                    .append("<td style='padding:6px;border:1px solid #ddd;text-align:right;color:#666'>")
                    .append(formatBillion(r.getPrevForeignInstNet())).append("억</td>")
                    .append("<td style='padding:6px;border:1px solid #ddd;text-align:right'>")
                    .append(r.turnoverBillion()).append("억</td>")
                    .append("<td style='padding:6px;border:1px solid #ddd;font-size:12px;color:#555'>")
                    .append(badges).append("(").append(r.getCriteriaPassed()).append("/3)</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
    }

    private String formatBillion(long won) {
        long billion = won / 100_000_000L;
        return String.format("%+,d", billion);
    }
}

package com.backtesting.service;

import com.backtesting.config.AlertProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final AlertProperties alertProps;

    @Value("${spring.mail.username:}")
    private String from;

    /** 수신지·발신지가 설정되지 않았으면 false. 앱 기동은 되어야 하므로 설정 누락은 런타임 skip. */
    public boolean isConfigured() {
        return from != null && !from.isBlank()
                && alertProps.getRecipient() != null && !alertProps.getRecipient().isBlank();
    }

    public void sendHtml(String subject, String html) {
        if (!isConfigured()) {
            log.warn("Email not configured (GMAIL_USERNAME/ALERT_RECIPIENT missing), skipping: {}", subject);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(alertProps.getRecipient());
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("Email sent: {} -> {}", subject, alertProps.getRecipient());
        } catch (Exception e) {
            log.error("Email send failed: {}", e.getMessage(), e);
        }
    }
}

package com.backtesting.persistence;

import com.backtesting.model.AuditLogEntry;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * API 감사 로그 영속화.
 * AuditLogService가 기존 ArrayList로 메모리만 쓰던 것을 DB로 옮김.
 * 오래된 행은 정기 truncate (retention N일).
 */
@Entity
@Table(name = "audit_log",
       indexes = {
         @Index(name = "idx_audit_ts", columnList = "ts DESC"),
         @Index(name = "idx_audit_level", columnList = "log_level")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogRowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts", nullable = false)
    private LocalDateTime timestamp;

    @Column(length = 10)
    private String method;

    @Column(length = 255)
    private String path;

    @Column(length = 128)
    private String action;

    @Column(name = "log_user", length = 64)
    private String user;

    @Column(length = 4000)
    private String requestBody;

    private int responseStatus;
    private long durationMs;

    @Column(name = "log_level", length = 10)
    private String level;

    @Column(length = 2048)
    private String detail;

    public static AuditLogRowEntity fromDomain(AuditLogEntry e) {
        return AuditLogRowEntity.builder()
                .timestamp(e.getTimestamp())
                .method(e.getMethod())
                .path(truncate(e.getPath(), 255))
                .action(truncate(e.getAction(), 128))
                .user(truncate(e.getUser(), 64))
                .requestBody(truncate(e.getRequestBody(), 4000))
                .responseStatus(e.getResponseStatus())
                .durationMs(e.getDurationMs())
                .level(truncate(e.getLevel(), 10))
                .detail(truncate(e.getDetail(), 2048))
                .build();
    }

    public AuditLogEntry toDomain() {
        return AuditLogEntry.builder()
                .id(id == null ? 0L : id)
                .timestamp(timestamp)
                .method(method)
                .path(path)
                .action(action)
                .user(user)
                .requestBody(requestBody)
                .responseStatus(responseStatus)
                .durationMs(durationMs)
                .level(level)
                .detail(detail)
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

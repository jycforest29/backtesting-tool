package com.backtesting.security;

import com.backtesting.common.error.ErrorCode;
import com.backtesting.common.error.ErrorResponseWriter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 클라이언트 단위 HTTP 레이트리밋. Bucket4j in-memory 토큰 버킷.
 *
 * 키 순서: 인증된 principal > X-API-Key(미인증이지만 키 제공 시) > IP.
 * 엔드포인트 분류: trading (보수), default (일반). actuator/health 는 건너뜀.
 *
 * 단일 인스턴스 배포 전제. 다중 인스턴스로 확장 시 bucket4j-redis 등으로 교체.
 * LOW-PRIORITY: 현재 구현은 /actuator/metrics 를 통해 활성 버킷 수를 gauge 로 노출 가능.
 */
/*
 * Spring Security 체인의 ApiKeyAuthenticationFilter 바로 다음에 등록된다
 * (SecurityConfig#securityFilterChain 참고). 인증 정보를 활용해 per-principal 버킷 키잉.
 * 따라서 @Component 로 자동 등록하지 않는다 — 중복 등록 방지.
 */
@Slf4j
public class HttpRateLimitFilter extends OncePerRequestFilter {

    private final HttpRateLimitProperties props;

    /** key → bucket. 메모리 폭주 방지: maxBuckets 초과 시 가장 오래 사용되지 않은 것부터 퇴거. */
    private final Map<String, BucketHolder> buckets = new ConcurrentHashMap<>();

    public HttpRateLimitFilter(HttpRateLimitProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!props.isEnabled()) return true;
        String path = req.getRequestURI();
        // health, prometheus — 모니터링 경로는 rate limit 대상 외.
        return path.startsWith("/actuator/health")
                || path.equals("/actuator/info")
                || path.equals("/actuator/prometheus")
                || HttpMethod.OPTIONS.matches(req.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String key = resolveKey(req);
        HttpRateLimitProperties.Bucket cfg = resolveBucketConfig(req);
        Bucket bucket = acquire(key, cfg);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retrySec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("HTTP rate limit exceeded key={} path={} retryAfterSec={}",
                    maskKey(key), req.getRequestURI(), retrySec);
            res.setHeader("X-Rate-Limit-Remaining", "0");
            ErrorResponseWriter.write(req, res, ErrorCode.RATE_LIMITED,
                    "요청 한도 초과 — " + retrySec + "초 후 재시도", retrySec);
            return;
        }

        res.setHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
        chain.doFilter(req, res);
    }

    // ------- helpers -------

    /**
     * 버킷 키: 우선순위대로 principal > X-API-Key > IP.
     * 인증 없이 동일 API 키만 반복 사용 시에도 키 단위로 집계되도록 X-API-Key 해시 활용.
     */
    private String resolveKey(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return "u:" + auth.getName();
        }
        String apiKey = req.getHeader(ApiKeyAuthenticationFilter.HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "k:" + Integer.toHexString(apiKey.hashCode());
        }
        return "i:" + clientIp(req);
    }

    private HttpRateLimitProperties.Bucket resolveBucketConfig(HttpServletRequest req) {
        String path = req.getRequestURI();
        String method = req.getMethod();
        // POST /api/trading/order, POST /api/trading/oco, POST /api/strategy-events/** 만 엄격.
        if (HttpMethod.POST.matches(method) && (
                path.equals("/api/trading/order")
                        || path.equals("/api/trading/oco")
                        || path.startsWith("/api/strategy-events/"))) {
            return props.getTrading();
        }
        return props.getDefault();
    }

    private Bucket acquire(String key, HttpRateLimitProperties.Bucket cfg) {
        evictIfNeeded();
        BucketHolder holder = buckets.compute(key, (k, existing) -> {
            if (existing != null && existing.matchesConfig(cfg)) {
                existing.touch();
                return existing;
            }
            Bandwidth bandwidth = Bandwidth.builder()
                    .capacity(cfg.getCapacity())
                    .refillIntervally(cfg.getRefillPerMinute(), Duration.ofMinutes(1))
                    .build();
            return new BucketHolder(Bucket.builder().addLimit(bandwidth).build(), cfg);
        });
        return holder.bucket;
    }

    private void evictIfNeeded() {
        if (buckets.size() <= props.getMaxBuckets()) return;
        long cutoff = System.currentTimeMillis() - props.getIdleExpiryMinutes() * 60_000L;
        buckets.entrySet().removeIf(e -> e.getValue().lastUsed.get() < cutoff);
    }

    private static String clientIp(HttpServletRequest req) {
        // 프록시 뒤에 있을 수도 있으므로 X-Forwarded-For 를 먼저 확인. 신뢰 프록시 범위는 운영자가 보장.
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 4) return "***";
        return key.substring(0, 2) + "***" + key.substring(key.length() - 2);
    }

    private static final class BucketHolder {
        final Bucket bucket;
        final int capacity;
        final int refillPerMinute;
        final AtomicLong lastUsed;

        BucketHolder(Bucket bucket, HttpRateLimitProperties.Bucket cfg) {
            this.bucket = bucket;
            this.capacity = cfg.getCapacity();
            this.refillPerMinute = cfg.getRefillPerMinute();
            this.lastUsed = new AtomicLong(System.currentTimeMillis());
        }

        boolean matchesConfig(HttpRateLimitProperties.Bucket cfg) {
            return cfg.getCapacity() == capacity && cfg.getRefillPerMinute() == refillPerMinute;
        }

        void touch() { lastUsed.set(System.currentTimeMillis()); }
    }
}

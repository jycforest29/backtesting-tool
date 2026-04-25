package com.backtesting.service.kis;

import com.backtesting.common.RequestContext;
import com.backtesting.config.KisProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KIS REST 호출 공통 로직 (rate limit, 재시도, 헤더 조립).
 * 서비스 계층은 extends 대신 composition으로 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisHttpCaller {

    private final KisRateLimiter rateLimiter;
    private final Tracer tracer;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 기본 HTTP 타임아웃 — deadline context 없을 때의 fallback. */
    private static final long DEFAULT_TIMEOUT_MS = 20_000;

    /** KIS 일반 API 호출. rate limiter 통과 → 전송 → EGW00201 방어 재시도 1회. */
    public JsonNode get(String url, Map<String, String> params, Map<String, String> headers) throws Exception {
        RequestContext.requireNotExpired();
        String qs = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        URI uri = URI.create(url + (qs.isEmpty() ? "" : "?" + qs));
        Duration timeout = Duration.ofMillis(RequestContext.effectiveTimeoutMs(DEFAULT_TIMEOUT_MS));
        if (timeout.isZero()) throw new RequestContext.DeadlineExceededException("no budget left for " + url);
        return send(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(timeout).GET();
            headers.forEach(b::header);
            return b.build();
        }, url);
    }

    public JsonNode post(String url, Object body, Map<String, String> headers) throws Exception {
        RequestContext.requireNotExpired();
        String json = mapper.writeValueAsString(body);
        Duration timeout = Duration.ofMillis(RequestContext.effectiveTimeoutMs(DEFAULT_TIMEOUT_MS));
        if (timeout.isZero()) throw new RequestContext.DeadlineExceededException("no budget left for " + url);
        return send(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            headers.forEach(b::header);
            return b.build();
        }, url);
    }

    private JsonNode send(RequestSupplier supplier, String url) throws Exception {
        Span span = tracer.spanBuilder("kis.http")
                .setAttribute("http.url", url)
                .setAttribute("peer.service", "kis")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            rateLimiter.acquireGeneral();
            HttpResponse<String> res = http.send(supplier.get(), HttpResponse.BodyHandlers.ofString());
            span.setAttribute("http.status_code", res.statusCode());
            if (res.statusCode() == 200 && !isRateLimit(res.body())) {
                return mapper.readTree(res.body());
            }
            if (isRateLimit(res.body())) {
                span.addEvent("rate_limited_retry");
                log.debug("EGW00201 slip-through, retry after 120ms: {}", url);
                Thread.sleep(120);
                rateLimiter.acquireGeneral();
                res = http.send(supplier.get(), HttpResponse.BodyHandlers.ofString());
                span.setAttribute("http.retry_status_code", res.statusCode());
                if (res.statusCode() == 200 && !isRateLimit(res.body())) {
                    return mapper.readTree(res.body());
                }
                if (isRateLimit(res.body())) {
                    span.setStatus(StatusCode.ERROR, "rate_limited");
                    throw new KisRateLimitedException(500);
                }
            }
            span.setStatus(StatusCode.ERROR, "http_" + res.statusCode());
            throw new RuntimeException("KIS " + url + " -> " + res.statusCode() + ": " + res.body());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public Map<String, String> baseHeaders(KisProperties props, String token, String trId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("authorization", "Bearer " + token);
        h.put("appkey", props.getAppKey());
        h.put("appsecret", props.getAppSecret());
        h.put("tr_id", trId);
        h.put("custtype", "P");
        return h;
    }

    public String tr(KisProperties props, String real, String paper) {
        return props.isPaperTrading() ? paper : real;
    }

    private boolean isRateLimit(String body) {
        return body != null && body.contains("EGW00201");
    }

    @FunctionalInterface
    private interface RequestSupplier {
        HttpRequest get();
    }
}

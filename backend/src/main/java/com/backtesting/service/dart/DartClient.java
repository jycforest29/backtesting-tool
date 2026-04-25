package com.backtesting.service.dart;

import com.backtesting.common.RequestContext;
import com.backtesting.config.DartProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DART OpenAPI HTTP 호출. 자체 rate limiter 내장 (KIS와 별도).
 * 키는 쿼리파라미터 crtfc_key=... 로 전달.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DartClient {

    private final DartProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<Instant> recent = new ArrayDeque<>();

    /** JSON 응답을 받는 GET. crtfc_key 자동 주입. */
    public synchronized JsonNode getJson(String path, Map<String, String> params) throws Exception {
        ensureConfigured();
        RequestContext.requireNotExpired();
        acquire();
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("crtfc_key", props.getApiKey());
        if (params != null) merged.putAll(params);
        String qs = merged.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        URI uri = URI.create(props.getBaseUrl() + path + "?" + qs);
        long timeoutMs = RequestContext.effectiveTimeoutMs(20_000);
        if (timeoutMs == 0) throw new RequestContext.DeadlineExceededException("no budget for DART " + path);
        HttpRequest req = HttpRequest.newBuilder().uri(uri).GET()
                .timeout(Duration.ofMillis(timeoutMs)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("DART " + path + " -> " + res.statusCode());
        }
        return mapper.readTree(res.body());
    }

    /** 원본 바이트(ex. corpCode.xml zip). crtfc_key 자동 주입. */
    public synchronized byte[] getBytes(String path, Map<String, String> params) throws Exception {
        ensureConfigured();
        acquire();
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("crtfc_key", props.getApiKey());
        if (params != null) merged.putAll(params);
        String qs = merged.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        URI uri = URI.create(props.getBaseUrl() + path + "?" + qs);
        HttpRequest req = HttpRequest.newBuilder().uri(uri).GET()
                .timeout(Duration.ofSeconds(60)).build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new RuntimeException("DART " + path + " -> " + res.statusCode());
        }
        return res.body();
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    private void ensureConfigured() {
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                    "DART_OPEN_API_KEY 환경변수가 설정되지 않았습니다. "
                    + "https://opendart.fss.or.kr/ 에서 발급 후 설정하세요.");
        }
    }

    /** 단순 슬라이딩 윈도우 rate limit. */
    private void acquire() throws InterruptedException {
        int rps = Math.max(1, props.getRequestsPerSecond());
        while (true) {
            Instant now = Instant.now();
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minusSeconds(1))) {
                recent.pollFirst();
            }
            if (recent.size() < rps) {
                recent.addLast(now);
                return;
            }
            long waitMs = 1000 - (now.toEpochMilli() - recent.peekFirst().toEpochMilli());
            if (waitMs > 0) Thread.sleep(waitMs);
        }
    }
}

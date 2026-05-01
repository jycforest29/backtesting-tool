package com.backtesting.service.kis;

import com.backtesting.common.error.ConfigurationMissingException;
import com.backtesting.config.KisProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisAuthService {

    private final KisProperties props;
    private final KisRateLimiter rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // 실전 도메인 전용 토큰 (해외 시세는 모의 도메인이 응답을 내주지 않아 분리 필요)
    private volatile String cachedRealToken;
    private volatile Instant realTokenExpiresAt = Instant.EPOCH;

    // WebSocket 전용 approval_key (24시간 유효)
    private volatile String cachedApprovalKey;
    private volatile Instant approvalKeyExpiresAt = Instant.EPOCH;

    @PostConstruct
    void loadFromDisk() {
        if (!props.isConfigured()) return;
        try {
            Path path = Path.of(props.getTokenCachePath());
            if (!Files.exists(path)) return;
            JsonNode node = mapper.readTree(Files.readString(path));
            String appKeyHash = node.path("appKeyHash").asText();
            if (!appKeyHash.equals(hashAppKey(props.getAppKey()))) {
                log.info("Token cache belongs to different app key, ignoring");
                return;
            }
            boolean paper = node.path("paper").asBoolean();
            if (paper != props.isPaperTrading()) return;
            long expMillis = node.path("expiresAt").asLong();
            String token = node.path("token").asText();
            if (token.isBlank() || expMillis < System.currentTimeMillis() + 300_000) return;
            this.cachedToken = token;
            this.tokenExpiresAt = Instant.ofEpochMilli(expMillis);
            log.info("Loaded cached KIS token from {} (expires {})",
                    props.getTokenCachePath(), tokenExpiresAt);
        } catch (Exception e) {
            log.warn("Failed to load cached token: {}", e.getMessage());
        }
    }

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(300))) {
            return cachedToken;
        }
        if (!props.isConfigured()) {
            throw new ConfigurationMissingException("KIS API",
                    "KIS API 키가 설정되지 않았습니다. HANTOO_API_KEY / HANTOO_API_SECRET / HANTOO_ACCOUNT 환경변수를 설정하세요.");
        }
        rateLimiter.acquireToken();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "grant_type", "client_credentials",
                    "appkey", props.getAppKey(),
                    "appsecret", props.getAppSecret()
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/oauth2/tokenP"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new RuntimeException("KIS token request failed: " + res.statusCode() + " " + res.body());
            }
            JsonNode json = mapper.readTree(res.body());
            cachedToken = json.path("access_token").asText();
            long ttl = json.path("expires_in").asLong(86400);
            tokenExpiresAt = Instant.now().plusSeconds(ttl);
            log.info("KIS access token refreshed (paper={}), expires in {}s", props.isPaperTrading(), ttl);
            saveToDisk();
            return cachedToken;
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain KIS access token: " + e.getMessage(), e);
        }
    }

    /**
     * 실전 도메인용 토큰. 해외 시세/일봉 조회는 모의 도메인에서 빈 응답을 주기 때문에
     * 반드시 실전 도메인 + 실전 토큰으로 호출해야 한다.
     * paperTrading=false면 일반 토큰과 동일하게 동작한다.
     */
    public synchronized String getRealAccessToken() {
        if (!props.isPaperTrading()) return getAccessToken();
        if (cachedRealToken != null && Instant.now().isBefore(realTokenExpiresAt.minusSeconds(300))) {
            return cachedRealToken;
        }
        if (!props.isConfigured()) {
            throw new ConfigurationMissingException("KIS API",
                    "KIS API 키가 설정되지 않았습니다. HANTOO_API_KEY / HANTOO_API_SECRET / HANTOO_ACCOUNT 환경변수를 설정하세요.");
        }
        rateLimiter.acquireToken();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "grant_type", "client_credentials",
                    "appkey", props.getAppKey(),
                    "appsecret", props.getAppSecret()
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrlReal() + "/oauth2/tokenP"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new RuntimeException("KIS real token request failed: " + res.statusCode() + " " + res.body());
            }
            JsonNode json = mapper.readTree(res.body());
            cachedRealToken = json.path("access_token").asText();
            long ttl = json.path("expires_in").asLong(86400);
            realTokenExpiresAt = Instant.now().plusSeconds(ttl);
            log.info("KIS real-domain token refreshed (for overseas quotes), expires in {}s", ttl);
            return cachedRealToken;
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain KIS real-domain token: " + e.getMessage(), e);
        }
    }

    private void saveToDisk() {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("token", cachedToken);
            node.put("expiresAt", tokenExpiresAt.toEpochMilli());
            node.put("paper", props.isPaperTrading());
            node.put("appKeyHash", hashAppKey(props.getAppKey()));
            Path path = Path.of(props.getTokenCachePath());
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, mapper.writeValueAsString(node));
            try { Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) {}
        } catch (Exception e) {
            log.warn("Failed to persist KIS token: {}", e.getMessage());
        }
    }

    /** AppKey는 저장하지 않고 짧은 해시만 저장 — 캐시 파일이 다른 키의 것인지 식별용. */
    private static String hashAppKey(String appKey) {
        if (appKey == null) return "";
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(appKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * KIS WebSocket 전용 approval_key. 일반 access_token과 별개. 24시간 유효.
     * 호출 한도 낮으므로 캐시 중요.
     */
    public synchronized String getApprovalKey() {
        if (cachedApprovalKey != null && Instant.now().isBefore(approvalKeyExpiresAt.minusSeconds(3600))) {
            return cachedApprovalKey;
        }
        if (!props.isConfigured()) {
            throw new ConfigurationMissingException("KIS API",
                    "WebSocket approval key 발급 불가 — HANTOO_API_KEY / HANTOO_API_SECRET 환경변수를 설정하세요.");
        }
        rateLimiter.acquireToken();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "grant_type", "client_credentials",
                    "appkey", props.getAppKey(),
                    "secretkey", props.getAppSecret()
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/oauth2/Approval"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new RuntimeException("KIS approval key request failed: " + res.statusCode() + " " + res.body());
            }
            JsonNode json = mapper.readTree(res.body());
            cachedApprovalKey = json.path("approval_key").asText();
            if (cachedApprovalKey.isBlank()) {
                throw new RuntimeException("approval_key empty in response: " + res.body());
            }
            approvalKeyExpiresAt = Instant.now().plusSeconds(86400);
            log.info("KIS WebSocket approval key obtained (expires in 24h)");
            return cachedApprovalKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain KIS approval key: " + e.getMessage(), e);
        }
    }

    /** Hashkey is required for POST order endpoints. */
    public String hashkey(Object body) {
        try {
            rateLimiter.acquireToken(); // hashkey도 토큰 버킷에 포함 (보수적)
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/uapi/hashkey"))
                    .header("Content-Type", "application/json")
                    .header("appkey", props.getAppKey())
                    .header("appsecret", props.getAppSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(res.body());
            return node.path("HASH").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hashkey: " + e.getMessage(), e);
        }
    }

    public KisProperties getProps() {
        return props;
    }
}

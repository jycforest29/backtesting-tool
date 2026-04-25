package com.backtesting.service.kis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * KIS 국내 실시간 체결가 WebSocket 클라이언트.
 * TR_ID: H0STCNT0 (주식체결). 최대 구독 종목 41개 제한 (KIS 기본).
 * 연결 끊김 시 지수 백오프 재연결, 기존 구독 종목 자동 재구독.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KisWebSocketClient {

    private final KisAuthService auth;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile WebSocket socket;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = ConcurrentHashMap.newKeySet(); // 연결 전 구독 요청 대기
    private final List<BiConsumer<String, BigDecimal>> tickListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kis-ws-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final StringBuilder partialBuffer = new StringBuilder();
    private int reconnectAttempts = 0;

    /** 체결가 틱 리스너 등록. (code, price) → 동작. */
    public void addTickListener(BiConsumer<String, BigDecimal> listener) {
        tickListeners.add(listener);
    }

    /**
     * 리스너 해제. 셧다운 시 구독자가 없어진 뒤에도 틱이 들어오면 NPE 위험 — @PreDestroy 에서 호출.
     * identity 비교 — 반드시 등록 시 전달한 동일 람다 / 메서드 레퍼런스를 전달해야 한다.
     */
    public void removeTickListener(BiConsumer<String, BigDecimal> listener) {
        tickListeners.remove(listener);
    }

    public synchronized void subscribe(String code) {
        if (subscribed.contains(code)) return;
        if (socket == null) {
            pending.add(code);
            connect();
            return;
        }
        sendSubscribe(code, "1");
        subscribed.add(code);
    }

    public synchronized void unsubscribe(String code) {
        if (!subscribed.contains(code)) {
            pending.remove(code);
            return;
        }
        if (socket != null) sendSubscribe(code, "2");
        subscribed.remove(code);
    }

    public Set<String> getSubscribed() {
        return Set.copyOf(subscribed);
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        try {
            String approval = auth.getApprovalKey();
            String url = auth.getProps().getWsUrl() + "/tryitout";
            log.info("KIS WS connecting to {}", url);
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new Listener(approval))
                    .whenComplete((ws, err) -> {
                        connecting.set(false);
                        if (err != null) {
                            log.warn("KIS WS connect failed: {}", err.getMessage());
                            scheduleReconnect();
                        } else {
                            socket = ws;
                            reconnectAttempts = 0;
                            log.info("KIS WS connected");
                            // 대기 중이던 + 기존 구독분 모두 송신
                            Set<String> toSub = new HashSet<>();
                            toSub.addAll(subscribed);
                            toSub.addAll(pending);
                            pending.clear();
                            toSub.forEach(code -> {
                                sendSubscribe(code, "1");
                                subscribed.add(code);
                            });
                        }
                    });
        } catch (Exception e) {
            connecting.set(false);
            log.error("KIS WS connect exception: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (subscribed.isEmpty() && pending.isEmpty()) return;
        reconnectAttempts++;
        long delay = Math.min(60, (long) Math.pow(2, Math.min(reconnectAttempts, 6)));
        log.info("KIS WS reconnecting in {}s (attempt {})", delay, reconnectAttempts);
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void sendSubscribe(String code, String trType) {
        try {
            String approval = auth.getApprovalKey();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("approval_key", approval);
            header.put("custtype", "P");
            header.put("tr_type", trType);   // 1=등록, 2=해제
            header.put("content-type", "utf-8");

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tr_id", "H0STCNT0");
            input.put("tr_key", code);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("input", input);

            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("header", header);
            frame.put("body", body);

            String json = mapper.writeValueAsString(frame);
            socket.sendText(json, true);
            log.debug("KIS WS {} {} sent", trType.equals("1") ? "SUB" : "UNSUB", code);
        } catch (Exception e) {
            log.warn("KIS WS send failed: {}", e.getMessage());
        }
    }

    private void onRawMessage(String message) {
        try {
            // KIS WS: PINGPONG이나 구독 응답은 JSON, 실시간 체결 데이터는 파이프 구분 raw
            if (message.startsWith("{")) {
                JsonNode json = mapper.readTree(message);
                String trId = json.path("header").path("tr_id").asText("");
                if ("PINGPONG".equals(trId)) {
                    // 서버에서 PINGPONG 오면 그대로 돌려줌
                    if (socket != null) socket.sendText(message, true);
                    return;
                }
                // 구독 응답
                String rtCd = json.path("body").path("rt_cd").asText();
                if (!"0".equals(rtCd)) {
                    log.warn("KIS WS subscribe response: {}", json.path("body").path("msg1").asText());
                }
                return;
            }
            // 실시간 체결: 0|H0STCNT0|001|<data>
            // 또는 1|H0STCNT0|001|<encrypted> (암호화 — 일반 종목은 0)
            String[] parts = message.split("\\|", 4);
            if (parts.length < 4) return;
            if (!"0".equals(parts[0])) return; // 암호화된 데이터는 스킵 (주로 회원 정보용)
            if (!"H0STCNT0".equals(parts[1])) return;
            parseExecution(parts[3]);
        } catch (Exception e) {
            log.warn("KIS WS parse error: {}", e.getMessage());
        }
    }

    /**
     * H0STCNT0 체결 데이터 레이아웃 (^ 구분):
     * 0: MKSC_SHRN_ISCD (종목코드)
     * 1: STCK_CNTG_HOUR (체결시각)
     * 2: STCK_PRPR (현재가)
     * ... 이하 여러 필드. 여러 레코드가 한 프레임에 올 수도 있어 개수 필드(parts[2]) 활용해 분할해야 하지만
     * 여기선 단일 레코드 기준으로 가장 첫 레코드만 파싱 (단타 OCO 목적엔 충분).
     */
    private void parseExecution(String payload) {
        String[] fields = payload.split("\\^");
        if (fields.length < 3) return;
        String code = fields[0];
        String priceStr = fields[2];
        try {
            BigDecimal price = new BigDecimal(priceStr.trim());
            for (BiConsumer<String, BigDecimal> l : tickListeners) {
                try { l.accept(code, price); }
                catch (Exception e) { log.warn("tick listener error: {}", e.getMessage()); }
            }
        } catch (NumberFormatException e) {
            // 이상한 데이터 무시
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        } catch (Exception ignored) {}
        scheduler.shutdownNow();
    }

    private class Listener implements WebSocket.Listener {
        private final String approvalKey;
        Listener(String approvalKey) { this.approvalKey = approvalKey; }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialBuffer.append(data);
            if (last) {
                String full = partialBuffer.toString();
                partialBuffer.setLength(0);
                onRawMessage(full);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("KIS WS closed: {} {}", statusCode, reason);
            socket = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("KIS WS error: {}", error.getMessage());
            socket = null;
            scheduleReconnect();
        }
    }
}
